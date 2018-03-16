/*
 * Copyright (c) 2012-2018 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.rdbloader
package loaders

import java.sql.{ Timestamp => SqlTimestamp }

import cats.implicits._

import shapeless.tag
import shapeless.tag._

// This project
import config.{ CliConfig, Step }
import config.StorageTarget.{ PostgresqlConfig, RedshiftConfig }
import db.Entities._
import discovery.DataDiscovery


object Common {

  /** Main "atomic" table name */
  val EventsTable = "events"

  /** Table for load manifests */
  val ManifestTable = "manifest"

  /** Default name for temporary local table used for transient COPY */
  val TransitEventsTable = "temp_transit_events"

  val BeginTransaction: SqlString = SqlString.unsafeCoerce("BEGIN")
  val CommitTransaction: SqlString = SqlString.unsafeCoerce("COMMIT")

  /** ADT representing possible destination of events table */
  sealed trait EventsTable { def getDescriptor: String }
  case class AtomicEvents(schema: String) extends EventsTable {
    def getDescriptor: String = getEventsTable(schema)
  }
  case class TransitTable(schema: String) extends EventsTable {
    def getDescriptor: String = getTable(schema, TransitEventsTable)
  }

  def getTable(databaseSchema: String, tableName: String): String =
    if (databaseSchema.isEmpty) tableName
    else databaseSchema + "." + tableName

  /**
   * Correctly merge database schema and table name
   */
  def getEventsTable(databaseSchema: String): String =
    getTable(databaseSchema, EventsTable)

  /**
   * Correctly merge database schema and table name
   */
  def getManifestTable(databaseSchema: String): String =
    getTable(databaseSchema, ManifestTable)

  /**
   * Subpath to check `atomic-events` directory presence
   */
  val atomicSubpathPattern = "(.*)/(run=[0-9]{4}-[0-1][0-9]-[0-3][0-9]-[0-2][0-9]-[0-6][0-9]-[0-6][0-9]/atomic-events)/(.*)".r
  //                                    year     month      day        hour       minute     second

  /**
   * Process any valid storage target,
   * including discovering step and establishing SSH-tunnel
   *
   * @param cliConfig RDB Loader app configuration
   */
  def load(cliConfig: CliConfig, discovery: List[DataDiscovery]): LoaderAction[Unit] = {
    val loadDb = cliConfig.target match {
      case postgresqlTarget: PostgresqlConfig =>
        PostgresqlLoader.run(postgresqlTarget, cliConfig.steps, discovery)
      case redshiftTarget: RedshiftConfig =>
        RedshiftLoader.run(cliConfig.configYaml, redshiftTarget, cliConfig.steps, discovery)
    }

    Security.bracket(cliConfig.target.sshTunnel, loadDb)
  }

  /**
    * Choose a discovery strategy and perform it
    *
    * @param cliConfig RDB Loader app configuration
    */
  def discover(cliConfig: CliConfig): LoaderAction[List[DataDiscovery]] = {
    // Shortcuts
    val shredJob = cliConfig.configYaml.storage.versions.rdbShredder
    val region = cliConfig.configYaml.aws.s3.region
    val assets = cliConfig.configYaml.aws.s3.buckets.jsonpathAssets

    val target = (cliConfig.target.processingManifest, cliConfig.folder) match {
      case (None, Some(f)) =>
        DataDiscovery.InSpecificFolder(f)
      case (Some(_), f) =>
        DataDiscovery.ViaManifest(f)
      case (None, None) =>
        DataDiscovery.Global(cliConfig.configYaml.aws.s3.buckets.shredded.good)
    }

    cliConfig.target match {
      case _: RedshiftConfig =>
        val original = DataDiscovery.discoverFull(target, cliConfig.target.id, shredJob, region, assets)
        if (cliConfig.steps.contains(Step.ConsistencyCheck) && cliConfig.target.processingManifest.isEmpty)
          DataDiscovery.checkConsistency(original)
        else original
      case _: PostgresqlConfig =>
        // Safe to skip consistency check as whole folder will be downloaded
        DataDiscovery.discoverFull(target, cliConfig.target.id, shredJob, region, assets)
    }
  }

  /**
   * String representing valid SQL query/statement,
   * ready to be executed
   */
  type SqlString = String @@ SqlStringTag

  object SqlString extends tag.Tagger[SqlStringTag] {
    def unsafeCoerce(s: String) = apply(s)
  }

  sealed trait SqlStringTag

  /**
    * Check if entry already exists in load manifest
    * @param schema database schema, e.g. `atomic` to access manifest
    * @param eventsTable atomic data table, usually `events` or temporary tablename
    * @return true if manifest record exists and atomic data is empty, assuming folder contains no events
    *         and next manifest record shouldn't be written
    */
  def checkLoadManifest(schema: String, eventsTable: EventsTable, atomicSize: Option[Long]): LoaderAction[Boolean] = {
    for {
      latestAtomic <- getEtlTstamp(eventsTable)
      emptyLoad <- latestAtomic match {
        case Some(events) => for {
          item <- getManifestItem(schema, events.etlTstamp)
          empty <- item match {
            case Some(manifest) if manifest.etlTstamp == events.etlTstamp =>
              atomicSize match {
                case Some(s) if s > 0 =>    // This can be oversimplification as gzip files are > 0 bytes
                  val message = getLoadManifestMessage(manifest, s)
                  LoaderAction.liftE[Boolean](LoaderError.LoadManifestError(message).asLeft)
                case Some(0)  =>
                  val message = s"Load Manifest record for ${manifest.etlTstamp} already exists, but atomic folder is empty"
                  for { _ <- LoaderA.print(message).liftA } yield true
                case _ =>
                  val message = getLoadManifestMessage(manifest)
                  LoaderAction.liftE[Boolean](LoaderError.LoadManifestError(message).asLeft)
              }
            case _ => LoaderAction.lift(false)
          }
        } yield empty
        case None => LoaderAction.lift(false)
      }
    } yield emptyLoad
  }

  /** Get ETL timestamp of ongoing load */
  private[loaders] def getEtlTstamp(eventsTable: EventsTable): LoaderAction[Option[Timestamp]] = {
    val query =
      s"""SELECT etl_tstamp
         | FROM ${eventsTable.getDescriptor}
         | WHERE etl_tstamp IS NOT null
         | ORDER BY etl_tstamp DESC
         | LIMIT 1""".stripMargin

    LoaderA.executeQuery[Option[Timestamp]](SqlString.unsafeCoerce(query))
  }

  /** Get latest load manifest item */
  private[loaders] def getManifestItem(schema: String, etlTstamp: SqlTimestamp): LoaderAction[Option[LoadManifestItem]] = {
    val query =
      s"""SELECT *
         | FROM ${Common.getManifestTable(schema)}
         | WHERE etl_tstamp = '$etlTstamp'
         | ORDER BY etl_tstamp DESC
         | LIMIT 1""".stripMargin

    LoaderA.executeQuery[Option[LoadManifestItem]](SqlString.unsafeCoerce(query))
  }

  private def getLoadManifestMessage(manifest: LoadManifestItem, size: Long): String =
    s"Load Manifest record for ${manifest.etlTstamp} already exists. Cannot proceed to loading. " +
      s"This can be caused by missing events in this run, but atomic folder is not empty ($size bytes). " +
      s"Manifest item committed at ${manifest.commitTstamp} with ${manifest.eventCount} events " +
      s"and ${manifest.shreddedCardinality} shredded types. " +
      s"Use --skip ${Step.LoadManifestCheck.asString} to bypass this check"

  private def getLoadManifestMessage(manifest: LoadManifestItem): String =
    s"Load Manifest record for ${manifest.etlTstamp} already exists. Cannot proceed to loading. " +
      s"Manifest item committed at ${manifest.commitTstamp} with ${manifest.eventCount} events " +
      s"and ${manifest.shreddedCardinality} shredded types. " +
      s"Use --skip ${Step.LoadManifestCheck.asString} to bypass this check"
}
