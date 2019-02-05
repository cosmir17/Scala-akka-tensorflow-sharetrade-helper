import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.{BeforeAndAfterAll, FunSuite, WordSpecLike}
import akka.testkit.TestProbe
import akka.actor.Props

class TrainerRouterActorSpec extends TestKit(ActorSystem("TrainerRouterActorSpec"))
  with ImplicitSender with BeforeAndAfterAll with WordSpecLike {


//  val probe = TestProbe()
//  val actor = system.actorOf(Props(new TrainerChildActor(...) {
//    override def train() =
//      probe.ref ! msg
//  }))

}
