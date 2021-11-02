package org.sunbird.job.content.function

import akka.dispatch.ExecutionContexts
import com.google.gson.reflect.TypeToken
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.job.cache.{DataCache, RedisConnect}
import org.sunbird.job.content.publish.domain.Event
import org.sunbird.job.content.publish.helpers.CollectionPublisher
import org.sunbird.job.content.task.ContentPublishConfig
import org.sunbird.job.domain.`object`.{DefinitionCache, ObjectDefinition}
import org.sunbird.job.publish.core.{DefinitionConfig, ExtDataConfig, ObjectData}
import org.sunbird.job.publish.helpers.EcarPackageType
import org.sunbird.job.util._
import org.sunbird.job.{BaseProcessFunction, Metrics}

import java.lang.reflect.Type
import scala.concurrent.ExecutionContext

class CollectionPublishFunction(config: ContentPublishConfig, httpUtil: HttpUtil,
                                @transient var neo4JUtil: Neo4JUtil = null,
                                @transient var cassandraUtil: CassandraUtil = null,
                                @transient var esUtil: ElasticSearchUtil = null,
                                @transient var cloudStorageUtil: CloudStorageUtil = null,
                                @transient var definitionCache: DefinitionCache = null,
                                @transient var definitionConfig: DefinitionConfig = null)
                               (implicit val stringTypeInfo: TypeInformation[String])
  extends BaseProcessFunction[Event, String](config) with CollectionPublisher {

  private[this] val logger = LoggerFactory.getLogger(classOf[CollectionPublishFunction])
  val mapType: Type = new TypeToken[java.util.Map[String, AnyRef]]() {}.getType
  private var cache: DataCache = _
  private val COLLECTION_CACHE_KEY_PREFIX = "hierarchy_"
  private val COLLECTION_CACHE_KEY_SUFFIX = ":leafnodes"

  @transient var ec: ExecutionContext = _
  private val pkgTypes = List(EcarPackageType.SPINE.toString, EcarPackageType.ONLINE.toString)

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    cassandraUtil = new CassandraUtil(config.cassandraHost, config.cassandraPort)
    neo4JUtil = new Neo4JUtil(config.graphRoutePath, config.graphName)
    esUtil = new ElasticSearchUtil(config.esConnectionInfo, config.compositeSearchIndexName, config.compositeSearchIndexType)
    cloudStorageUtil = new CloudStorageUtil(config)
    ec = ExecutionContexts.global
    definitionCache = new DefinitionCache()
    definitionConfig = DefinitionConfig(config.schemaSupportVersionMap, config.definitionBasePath)
    cache = new DataCache(config, new RedisConnect(config), config.nodeStore, List())
    cache.init()
  }

  override def close(): Unit = {
    super.close()
    cassandraUtil.close()
    cache.close()
  }

  override def metricsList(): List[String] = {
    List(config.collectionPublishEventCount, config.collectionPublishSuccessEventCount, config.collectionPublishFailedEventCount, config.skippedEventCount)
  }

  override def processElement(data: Event, context: ProcessFunction[Event, String]#Context, metrics: Metrics): Unit = {
    try {
      val definition: ObjectDefinition = definitionCache.getDefinition(data.objectType, config.schemaSupportVersionMap.getOrElse(data.objectType.toLowerCase(), "1.0").asInstanceOf[String], config.definitionBasePath)
      val readerConfig = ExtDataConfig(config.hierarchyKeyspaceName, config.hierarchyTableName, definition.getExternalPrimaryKey, definition.getExternalProps)
      logger.info("Collection publishing started for : " + data.identifier)
      metrics.incCounter(config.collectionPublishEventCount)
      val obj: ObjectData = getObject(data.identifier, data.pkgVersion, data.mimeType, data.publishType, readerConfig)(neo4JUtil, cassandraUtil)
      val messages: List[String] = List.empty[String] // validate(obj, obj.identifier, validateMetadata)
      if (obj.pkgVersion > data.pkgVersion) {
        metrics.incCounter(config.skippedEventCount)
        logger.info(s"""pkgVersion should be greater than or equal to the obj.pkgVersion for : ${obj.identifier}""")
      } else {
        if (messages.isEmpty) {
          // Pre-publish update
          updateProcessingNode(new ObjectData(obj.identifier, obj.metadata ++ Map("lastPublishedBy" -> data.lastPublishedBy), obj.extData, obj.hierarchy))(neo4JUtil, cassandraUtil, readerConfig, definitionCache, definitionConfig)

          val isCollectionShallowCopy = isContentShallowCopy(obj)
          val updatedObj = if (isCollectionShallowCopy) updateOriginPkgVersion(obj)(neo4JUtil) else obj

          // Clear redis cache
          cache.del(data.identifier)
          cache.del(data.identifier + COLLECTION_CACHE_KEY_SUFFIX)
          cache.del(COLLECTION_CACHE_KEY_PREFIX + data.identifier)

          // Collection - add step to remove units of already Live content from redis - line 243 in PublishFinalizer
          val unitNodes = if (obj.identifier.endsWith(".img")) {
            val childNodes = getUnitsFromLiveContent(updatedObj)(cassandraUtil, readerConfig)
            childNodes.filter(rec => rec.nonEmpty).foreach(childId => cache.del(COLLECTION_CACHE_KEY_PREFIX + childId))
            childNodes.filter(rec => rec.nonEmpty)
          } else List.empty

          val enrichedObj = enrichObject(updatedObj)(neo4JUtil, cassandraUtil, readerConfig, cloudStorageUtil, config, definitionCache, definitionConfig)
          val objWithEcar = getObjectWithEcar(enrichedObj, pkgTypes)(ec, neo4JUtil, cassandraUtil, readerConfig, cloudStorageUtil, config, definitionCache, definitionConfig, httpUtil)
          logger.info("Ecar generation done for Collection: " + objWithEcar.identifier)

          saveOnSuccess(new ObjectData(objWithEcar.identifier, objWithEcar.metadata.-("children"), objWithEcar.extData, objWithEcar.hierarchy))(neo4JUtil, cassandraUtil, readerConfig, definitionCache, definitionConfig)

          val publishType = objWithEcar.getString("publish_type", "Public")
          val successObj = new ObjectData(objWithEcar.identifier, objWithEcar.metadata + ("status" -> (if (publishType.equalsIgnoreCase("Unlisted")) "Unlisted" else "Live")), objWithEcar.extData, objWithEcar.hierarchy)

          val children = successObj.hierarchy.getOrElse(Map()).getOrElse("children", List()).asInstanceOf[List[Map[String, AnyRef]]]
          // Collection - update and publish children - line 418 in PublishFinalizer
          val updatedChildren = updateHierarchyMetadata(children, successObj)(config)
          publishHierarchy(updatedChildren, successObj, readerConfig)(cassandraUtil)
          if (!isCollectionShallowCopy) syncNodes(updatedChildren, unitNodes)(esUtil, neo4JUtil, cassandraUtil, readerConfig, definition, config)

          metrics.incCounter(config.collectionPublishSuccessEventCount)
          logger.info("Collection publishing completed successfully for : " + data.identifier)
        } else {
          saveOnFailure(obj, messages)(neo4JUtil)
          metrics.incCounter(config.collectionPublishFailedEventCount)
          logger.info("Collection publishing failed for : " + data.identifier)
        }
      }
    } catch {
      case exp: Exception =>
        exp.printStackTrace()
        logger.info("CollectionPublishFunction::processElement::Exception" + exp.getMessage)
        throw exp
    }
  }

}
