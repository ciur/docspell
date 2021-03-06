package docspell.common

import cats.data.NonEmptyList
import docspell.common.MetaProposal.Candidate
import io.circe._
import io.circe.generic.semiauto._

case class MetaProposal(proposalType: MetaProposalType, values: NonEmptyList[Candidate]) {

  def addIdRef(refs: Seq[Candidate]): MetaProposal =
    copy(values = MetaProposal.flatten(values ++ refs.toList))

  def isSingleValue: Boolean =
    values.tail.isEmpty

  def isMultiValue: Boolean =
    !isSingleValue

  def size: Int =
    values.size
}

object MetaProposal {

  case class Candidate(ref: IdRef, origin: Set[NerLabel])
  object Candidate {
    implicit val jsonEncoder: Encoder[Candidate] =
      deriveEncoder[Candidate]
    implicit val jsonDecoder: Decoder[Candidate] =
      deriveDecoder[Candidate]
  }

  def flatten(s: NonEmptyList[Candidate]): NonEmptyList[Candidate] = {
    def append(list: List[Candidate]): Candidate =
      list.reduce((l0, l1) => l0.copy(origin = l0.origin ++ l1.origin))
    val grouped = s.toList.groupBy(_.ref.id)
    NonEmptyList.fromListUnsafe(grouped.values.toList.map(append))
  }

  implicit val jsonDecoder: Decoder[MetaProposal] =
    deriveDecoder[MetaProposal]
  implicit val jsonEncoder: Encoder[MetaProposal] =
    deriveEncoder[MetaProposal]
}
