import QDecisionPolicyActor._
import akka.actor.{ActorSystem, Props}
import akka.testkit.{EventFilter, ImplicitSender, TestKit}
import org.platanios.tensorflow.api.Tensor
import org.platanios.tensorflow.api.core.Shape
import org.scalatest.{BeforeAndAfterAll, OneInstancePerTest, WordSpecLike}

import scala.concurrent.duration._

class QDecisionPolicyActorSpec extends TestKit(ActorSystem("QDecisionPolicyActorSpec")) with ImplicitSender with WordSpecLike with BeforeAndAfterAll with OneInstancePerTest with InMemoryCleanup {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  val timeout = 5.seconds

  private val stateList = (55 until 256) ++ Seq(1000, 0) //203 size
  private val nextStateList = (55 until 256) ++ Seq(940, 1) //203 size
  private val currentStateTensor = Tensor[Float](stateList).reshape(Shape(-1, stateList.size))
  private val nextStateTensor = Tensor[Float](nextStateList).reshape(Shape(-1, nextStateList.size))

  "Decision policy actor" should {
    "have stateList and nextStateList sized 202" in {
      assert(stateList.size == 203)
      assert(nextStateList.size == 203)
    }

    "return action in initial stage" in {
      val decisionPolicyActor = system.actorOf(Props[QDecisionPolicyActor])
      decisionPolicyActor ! SelectionAction(currentStateTensor, 0)
      expectMsgType[Action]
    }

    "send Updated message to itself when Q value is newly updated" in {
      val decisionPolicyActor = system.actorOf(Props[QDecisionPolicyActor])
      decisionPolicyActor ! UpdateQ(currentStateTensor, 10, nextStateTensor)
      expectNoMessage()
    }

    "return an action after updateQ happens" in {
      val decisionPolicyActor = system.actorOf(Props[QDecisionPolicyActor])
      decisionPolicyActor ! UpdateQ(currentStateTensor, 10, nextStateTensor)
      expectNoMessage()
      decisionPolicyActor ! SelectionAction(currentStateTensor, 0)
      expectMsgType[Action]
    }

    "return InvalidDimension message when selectionAction contains state tensor with not expected shape" in {
      val decisionPolicyActor = system.actorOf(Props[QDecisionPolicyActor])
      val wrongStateList = (55 until 100) ++ Seq(1000, 0)
      val wrongCurrentStateTensor = Tensor[Float](wrongStateList).reshape(Shape(-1, wrongStateList.size))

      EventFilter[IllegalArgumentException](occurrences = 1) intercept {
        decisionPolicyActor ! SelectionAction(wrongCurrentStateTensor, 0)
      }
    }

    "return InvalidDimension message when UpdateQ contains state or nextState tensor with not expected shape" in {
      val decisionPolicyActor = system.actorOf(Props[QDecisionPolicyActor])
      val wrongStateList = (55 until 100) ++ Seq(1000, 0)
      val wrongCurrentStateTensor = Tensor[Float](wrongStateList).reshape(Shape(-1, wrongStateList.size))

      EventFilter[IllegalArgumentException](occurrences = 1) intercept {
        decisionPolicyActor ! UpdateQ(currentStateTensor, 10, wrongCurrentStateTensor)
      }
    }
  }

}

import akka.actor.ActorSystem
import akka.persistence.inmemory.extension.{InMemoryJournalStorage, InMemorySnapshotStorage, StorageExtension}
import akka.testkit.TestProbe
import org.scalatest.{BeforeAndAfterEach, Suite}

trait InMemoryCleanup extends BeforeAndAfterEach { _: Suite =>

  implicit def system: ActorSystem

  override protected def beforeEach(): Unit = {
    val tp = TestProbe()
    tp.send(StorageExtension(system).journalStorage, InMemoryJournalStorage.ClearJournal)
    tp.expectMsg(akka.actor.Status.Success(""))
    tp.send(StorageExtension(system).snapshotStorage, InMemorySnapshotStorage.ClearSnapshots)
    tp.expectMsg(akka.actor.Status.Success(""))
    super.beforeEach()
  }
}