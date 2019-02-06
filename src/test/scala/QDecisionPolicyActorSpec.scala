import QDecisionPolicyActor._
import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import org.platanios.tensorflow.api.Tensor
import org.platanios.tensorflow.api.core.Shape
import org.scalatest.{BeforeAndAfterAll, OneInstancePerTest, WordSpecLike}

class QDecisionPolicyActorSpec extends TestKit(ActorSystem("QDecisionPolicyActorSpec"))
  with ImplicitSender with WordSpecLike with BeforeAndAfterAll with OneInstancePerTest {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val stateList = (55 until 255) ++ Seq(1000, 0)
  private val nextStateList = (55 until 255) ++ Seq(940, 1)
  private val currentStateTensor = Tensor[Float](stateList).reshape(Shape(-1, stateList.size))
  private val nextStateTensor = Tensor[Float](nextStateList).reshape(Shape(-1, nextStateList.size))

  "Decision policy actor" should {
    "have stateList and nextStateList sized 202" in {
      assert(stateList.size == 202)
      assert(nextStateList.size == 202)
    }

    "return action in initial stage" in {
      val decisionPolicyActor = system.actorOf(Props[QDecisionPolicyActor])
      decisionPolicyActor ! SelectionAction(currentStateTensor, 0)
      expectMsgType[Action]
    }

    "send Updated message to itself when Q value is newly updated" in {
      val decisionPolicyActor = system.actorOf(Props[QDecisionPolicyActor])
      decisionPolicyActor ! UpdateQ(currentStateTensor, 10, nextStateTensor)
      expectMsg(Updated)
    }

    "return an action after updateQ happens" in {
      val decisionPolicyActor = system.actorOf(Props[QDecisionPolicyActor])
      decisionPolicyActor ! UpdateQ(currentStateTensor, 10, nextStateTensor)
      expectMsg(Updated)
      decisionPolicyActor ! SelectionAction(currentStateTensor, 0)
      expectMsgType[Action]
    }
  }
}