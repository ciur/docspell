package docspell.store.queries

import bitpeace.FileMeta
import cats.implicits._
import cats.effect.Sync
import fs2.Stream
import doobie._
import doobie.implicits._
import docspell.common.{IdRef, _}
import docspell.store.Store
import docspell.store.records._
import docspell.store.impl.Implicits._
import org.log4s._

object QItem {
  private[this] val logger = getLogger

  case class ItemData(
      item: RItem,
      corrOrg: Option[ROrganization],
      corrPerson: Option[RPerson],
      concPerson: Option[RPerson],
      concEquip: Option[REquipment],
      inReplyTo: Option[IdRef],
      tags: Vector[RTag],
      attachments: Vector[(RAttachment, FileMeta)]
  ) {

    def filterCollective(coll: Ident): Option[ItemData] =
      if (item.cid == coll) Some(this) else None
  }

  def findItem(id: Ident): ConnectionIO[Option[ItemData]] = {
    val IC  = RItem.Columns.all.map(_.prefix("i"))
    val OC  = ROrganization.Columns.all.map(_.prefix("o"))
    val P0C = RPerson.Columns.all.map(_.prefix("p0"))
    val P1C = RPerson.Columns.all.map(_.prefix("p1"))
    val EC  = REquipment.Columns.all.map(_.prefix("e"))
    val ICC = List(RItem.Columns.id, RItem.Columns.name).map(_.prefix("ref"))

    val cq = selectSimple(IC ++ OC ++ P0C ++ P1C ++ EC ++ ICC, RItem.table ++ fr"i", Fragment.empty) ++
      fr"LEFT JOIN" ++ ROrganization.table ++ fr"o ON" ++ RItem.Columns.corrOrg
      .prefix("i")
      .is(ROrganization.Columns.oid.prefix("o")) ++
      fr"LEFT JOIN" ++ RPerson.table ++ fr"p0 ON" ++ RItem.Columns.corrPerson
      .prefix("i")
      .is(RPerson.Columns.pid.prefix("p0")) ++
      fr"LEFT JOIN" ++ RPerson.table ++ fr"p1 ON" ++ RItem.Columns.concPerson
      .prefix("i")
      .is(RPerson.Columns.pid.prefix("p1")) ++
      fr"LEFT JOIN" ++ REquipment.table ++ fr"e ON" ++ RItem.Columns.concEquipment
      .prefix("i")
      .is(REquipment.Columns.eid.prefix("e")) ++
      fr"LEFT JOIN" ++ RItem.table ++ fr"ref ON" ++ RItem.Columns.inReplyTo
      .prefix("i")
      .is(RItem.Columns.id.prefix("ref")) ++
      fr"WHERE" ++ RItem.Columns.id.prefix("i").is(id)

    val q = cq
      .query[
        (
            RItem,
            Option[ROrganization],
            Option[RPerson],
            Option[RPerson],
            Option[REquipment],
            Option[IdRef]
        )
      ]
      .option
    val attachs = RAttachment.findByItemWithMeta(id)

    val tags = RTag.findByItem(id)

    for {
      data <- q
      att  <- attachs
      ts   <- tags
    } yield data.map(d => ItemData(d._1, d._2, d._3, d._4, d._5, d._6, ts, att))
  }

  case class ListItem(
      id: Ident,
      name: String,
      state: ItemState,
      date: Timestamp,
      dueDate: Option[Timestamp],
      source: String,
      direction: Direction,
      created: Timestamp,
      fileCount: Int,
      corrOrg: Option[IdRef],
      corrPerson: Option[IdRef],
      concPerson: Option[IdRef],
      concEquip: Option[IdRef]
  )

  case class Query(
      collective: Ident,
      name: Option[String],
      states: Seq[ItemState],
      direction: Option[Direction],
      corrPerson: Option[Ident],
      corrOrg: Option[Ident],
      concPerson: Option[Ident],
      concEquip: Option[Ident],
      tagsInclude: List[Ident],
      tagsExclude: List[Ident],
      dateFrom: Option[Timestamp],
      dateTo: Option[Timestamp],
      dueDateFrom: Option[Timestamp],
      dueDateTo: Option[Timestamp]
  )

  def findItems(q: Query): Stream[ConnectionIO, ListItem] = {
    val IC         = RItem.Columns
    val AC         = RAttachment.Columns
    val PC         = RPerson.Columns
    val OC         = ROrganization.Columns
    val EC         = REquipment.Columns
    val itemCols   = IC.all
    val personCols = List(RPerson.Columns.pid, RPerson.Columns.name)
    val orgCols    = List(ROrganization.Columns.oid, ROrganization.Columns.name)
    val equipCols  = List(REquipment.Columns.eid, REquipment.Columns.name)

    val finalCols = commas(
      IC.id.prefix("i").f,
      IC.name.prefix("i").f,
      IC.state.prefix("i").f,
      coalesce(IC.itemDate.prefix("i").f, IC.created.prefix("i").f),
      IC.dueDate.prefix("i").f,
      IC.source.prefix("i").f,
      IC.incoming.prefix("i").f,
      IC.created.prefix("i").f,
      fr"COALESCE(a.num, 0)",
      OC.oid.prefix("o0").f,
      OC.name.prefix("o0").f,
      PC.pid.prefix("p0").f,
      PC.name.prefix("p0").f,
      PC.pid.prefix("p1").f,
      PC.name.prefix("p1").f,
      EC.eid.prefix("e1").f,
      EC.name.prefix("e1").f
    )

    val withItem   = selectSimple(itemCols, RItem.table, IC.cid.is(q.collective))
    val withPerson = selectSimple(personCols, RPerson.table, PC.cid.is(q.collective))
    val withOrgs   = selectSimple(orgCols, ROrganization.table, OC.cid.is(q.collective))
    val withEquips = selectSimple(equipCols, REquipment.table, EC.cid.is(q.collective))
    val withAttach = fr"SELECT COUNT(" ++ AC.id.f ++ fr") as num, " ++ AC.itemId.f ++
      fr"from" ++ RAttachment.table ++ fr"GROUP BY (" ++ AC.itemId.f ++ fr")"

    val query = withCTE(
      "items"   -> withItem,
      "persons" -> withPerson,
      "orgs"    -> withOrgs,
      "equips"  -> withEquips,
      "attachs" -> withAttach
    ) ++
      fr"SELECT DISTINCT" ++ finalCols ++ fr" FROM items i" ++
      fr"LEFT JOIN attachs a ON" ++ IC.id.prefix("i").is(AC.itemId.prefix("a")) ++
      fr"LEFT JOIN persons p0 ON" ++ IC.corrPerson
      .prefix("i")
      .is(PC.pid.prefix("p0")) ++ // i.corrperson = p0.pid" ++
      fr"LEFT JOIN orgs o0 ON" ++ IC.corrOrg
      .prefix("i")
      .is(OC.oid.prefix("o0")) ++ // i.corrorg = o0.oid" ++
      fr"LEFT JOIN persons p1 ON" ++ IC.concPerson
      .prefix("i")
      .is(PC.pid.prefix("p1")) ++ // i.concperson = p1.pid" ++
      fr"LEFT JOIN equips e1 ON" ++ IC.concEquipment
      .prefix("i")
      .is(EC.eid.prefix("e1")) // i.concequipment = e1.eid"

    // inclusive tags are AND-ed
    val tagSelectsIncl = q.tagsInclude
      .map(tid =>
        selectSimple(List(RTagItem.Columns.itemId), RTagItem.table, RTagItem.Columns.tagId.is(tid))
      )
      .map(f => sql"(" ++ f ++ sql") ")

    // exclusive tags are OR-ed
    val tagSelectsExcl =
      if (q.tagsExclude.isEmpty) Fragment.empty
      else
        selectSimple(
          List(RTagItem.Columns.itemId),
          RTagItem.table,
          RTagItem.Columns.tagId.isOneOf(q.tagsExclude)
        )

    val name = q.name.map(queryWildcard)
    val cond = and(
      IC.cid.prefix("i").is(q.collective),
      IC.state.prefix("i").isOneOf(q.states),
      IC.incoming.prefix("i").isOrDiscard(q.direction),
      name.map(n => IC.name.prefix("i").lowerLike(n)).getOrElse(Fragment.empty),
      RPerson.Columns.pid.prefix("p0").isOrDiscard(q.corrPerson),
      ROrganization.Columns.oid.prefix("o0").isOrDiscard(q.corrOrg),
      RPerson.Columns.pid.prefix("p1").isOrDiscard(q.concPerson),
      REquipment.Columns.eid.prefix("e1").isOrDiscard(q.concEquip),
      if (q.tagsInclude.isEmpty) Fragment.empty
      else
        IC.id.prefix("i") ++ sql" IN (" ++ tagSelectsIncl.reduce(_ ++ fr"INTERSECT" ++ _) ++ sql")",
      if (q.tagsExclude.isEmpty) Fragment.empty
      else IC.id.prefix("i").f ++ sql" NOT IN (" ++ tagSelectsExcl ++ sql")",
      q.dateFrom
        .map(d => coalesce(IC.itemDate.prefix("i").f, IC.created.prefix("i").f) ++ fr">= $d")
        .getOrElse(Fragment.empty),
      q.dateTo
        .map(d => coalesce(IC.itemDate.prefix("i").f, IC.created.prefix("i").f) ++ fr"<= $d")
        .getOrElse(Fragment.empty),
      q.dueDateFrom.map(d => IC.dueDate.prefix("i").isGt(d)).getOrElse(Fragment.empty),
      q.dueDateTo.map(d => IC.dueDate.prefix("i").isLt(d)).getOrElse(Fragment.empty)
    )

    val order = orderBy(coalesce(IC.itemDate.prefix("i").f, IC.created.prefix("i").f) ++ fr"DESC")
    val frag  = query ++ fr"WHERE" ++ cond ++ order
    logger.trace(s"List items: $frag")
    frag.query[ListItem].stream
  }

  def delete[F[_]: Sync](store: Store[F])(itemId: Ident, collective: Ident): F[Int] =
    for {
      tn <- store.transact(RTagItem.deleteItemTags(itemId))
      rn <- QAttachment.deleteItemAttachments(store)(itemId, collective)
      n  <- store.transact(RItem.deleteByIdAndCollective(itemId, collective))
    } yield tn + rn + n

  def findByFileIds(fileMetaIds: List[Ident]): ConnectionIO[Vector[RItem]] = {
    val IC = RItem.Columns
    val AC = RAttachment.Columns
    val q = fr"SELECT DISTINCT" ++ commas(IC.all.map(_.prefix("i").f)) ++ fr"FROM" ++ RItem.table ++ fr"i" ++
      fr"INNER JOIN" ++ RAttachment.table ++ fr"a ON" ++ AC.itemId
      .prefix("a")
      .is(IC.id.prefix("i")) ++
      fr"WHERE" ++ AC.fileId.isOneOf(fileMetaIds) ++ orderBy(IC.created.prefix("i").asc)

    q.query[RItem].to[Vector]
  }

  def findByChecksum(checksum: String, collective: Ident): ConnectionIO[Vector[RItem]] = {
    val IC    = RItem.Columns.all.map(_.prefix("i"))
    val aItem = RAttachment.Columns.itemId.prefix("a")
    val iId   = RItem.Columns.id.prefix("i")
    val iColl = RItem.Columns.cid.prefix("i")

    val from = RItem.table ++ fr"i INNER JOIN" ++ RAttachment.table ++ fr"a ON" ++ aItem.is(iId) ++
      fr"INNER JOIN filemeta m ON m.id = a.filemetaid"
    selectSimple(IC, from, and(fr"m.checksum = $checksum", iColl.is(collective)))
      .query[RItem]
      .to[Vector]
  }

  private def queryWildcard(value: String): String = {
    def prefix(n: String) =
      if (n.startsWith("*")) s"%${n.substring(1)}"
      else n

    def suffix(n: String) =
      if (n.endsWith("*")) s"${n.dropRight(1)}%"
      else n

    prefix(suffix(value))
  }

}
