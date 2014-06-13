import akka.actor.ActorDSL._
import akka.actor.ActorSystem
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import akka.pattern.ask
import akka.util.Timeout
import akka.actor.{Actor, ActorRef, FSM, Cancellable, ActorLogging, Props, PoisonPill}
import akka.actor.Actor.Receive
import scala.concurrent.forkjoin.ThreadLocalRandom

object DemoImplicits {
  implicit val system = ActorSystem("demo")

  implicit val timeout = new Timeout(1.seconds)

  implicit val ec: ExecutionContext = system.dispatcher

  def askActor(a: ActorRef, msg: Any) = (a ? msg).onSuccess { case answer => println(answer) }
}

object MusicalChairs {
  case object MusicStopped
  case class SitDown(name: String)

  object Moderator {
    case object StartGame
    case class JoinGame(name: String)
    private case object Tick
    def props: Props = Props(new Moderator)
  }

  class Moderator extends Actor with ActorLogging {
    import Moderator._
    import Player._
    import context.dispatcher

    var cancelable: Option[Cancellable] = None
    private def randomizer = ThreadLocalRandom.current()
    var players = Map.empty[String, ActorRef]

    def idle: Receive = {
      case JoinGame(name) =>
        log.info(s"$name joined")
        players += name -> context.actorOf(Player.props(name))
      case StartGame =>
        val playerCount = players.size
        if (playerCount == 1) {
          val winnerName = players.head._1
          log.info(s"$winnerName won the game!")
        }
        else {
          val turns = randomizer.nextInt(10)
          cancelable = Some(context.system.scheduler.schedule(1.seconds, 1.seconds, self, Tick))
          log.info(s"playing music for $turns turns")
          context.become(playMusic(turns, playerCount - 1))
        }
    }

    def playMusic(turns: Int, chairs: Int): Receive = {
      case Tick if turns <= 0 =>
        log.info("done playing music")
        cancelable.foreach { _.cancel() }
        context.children.foreach {_ ! MusicStopped }
        context.become(seeWhoSitsDown(chairs))

      case Tick =>
        log.info("playing music")
        context.become(playMusic(turns - 1, chairs))
    }

    def seeWhoSitsDown(chairs: Int): Receive = {
      case SitDown(name) if chairs <= 0 =>
        log.info(s"$name can't find a chair to sit in")
        sender ! PoisonPill
        players -= name
        context.become(idle)
        self ! StartGame
      case SitDown(name) => 
        log.info(s"$name sits down")
        context.become(seeWhoSitsDown(chairs - 1))
    }

    def receive = idle
  }

  object Player {
    def props(name: String): Props = Props(new Player(name))
  }

  class Player(name: String) extends Actor with ActorLogging {
    import Player._

    override def preStart = { log.info(s"$name is in the game") }
    override def postStop = { log.info(s"$name is out of the game") }

    def receive: Receive = {
      case MusicStopped =>
        log.info(s"$name heard the music stop and tries to sit down")
        context.parent ! SitDown(name)
    }
  }

}

import DemoImplicits._
import MusicalChairs._
