package interpreter

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import akka.util.Timeout
import akka.actor._
import akka.pattern.{Patterns, ask}
import syntax._

class PiLauncher(p: Pi, onCompletion: Function1[Pi, Unit]) {

  val (system: ActorSystem, creationManager: ActorRef) = {

    val sys: ActorSystem = ActorSystem("PiLauncher")
    sys.registerOnTermination(new Runnable {
      def run: Unit = onCompletion(result.get)
    })
    val creationManager: ActorRef =
      sys.actorOf(Props(classOf[PiCreationManager], this), "PiCreationManager")
    val initChanMap: Map[Name, ActorRef] = (p.free map { case n =>
      (n, sys.actorOf(Props(classOf[PiChannel], creationManager),
        s"PiChannelI${n.id}")) }).toMap
    creationManager ! SetLiveActors(initChanMap.values.toSet)
    creationManager ! MakeRunner(initChanMap, p)
    sys.scheduler.schedule(3.seconds, 3.seconds, creationManager,
      CheckFinished)(sys.dispatcher)

    (sys, creationManager)
  }

  var result: Option[Pi] = None
}

// Serves as parent actor for other actors. Keeps track of channels and runners.
// Can be queried for deadlock detection.
class PiCreationManager(launcher: PiLauncher) extends Actor {

  var liveActors: Set[ActorRef] = Set.empty
  var result: List[Pi] = Nil
  var sendSinceTimerReset: Boolean = false
  var reportTo: Option[ActorRef] = None

  def receive: Receive = setLiveActors

  def setLiveActors: Receive = {
    case SetLiveActors(set) => {
      this.liveActors = set
      context.become(mainReceive)
      this.reportTo = Some(sender)
    }
  }

  def mainReceive: Receive = {
    case SendOccurred => sendSinceTimerReset = true
    case CheckFinished => {
      if (sendSinceTimerReset) sendSinceTimerReset = false
      else this.liveActors map { case p => p ! ForceReportStop }
    }
    case ReportStop(p) => {
      this.result = this.result ++ p.toList
      this.liveActors = this.liveActors - sender
      Await.result(Patterns.gracefulStop(sender, 10.seconds), Duration.Inf)
      if (this.liveActors.isEmpty) {
        launcher.result = Some(Pi fromList this.result)
        context.system.shutdown
      }
    }
    case MakeChannel => {
      val newChannel: ActorRef = context.actorOf(Props(classOf[PiChannel],
        self), s"PiChannel${this.liveActors.size}")
      sender ! MakeChannelResponse(newChannel)
      this.liveActors = this.liveActors + newChannel
    }
    case MakeRunner(chanMap, p) => {
      val newRunner: ActorRef = context.actorOf(Props(classOf[PiRunner],
        chanMap, p, self), s"PiRunner${this.liveActors.size}")
      newRunner ! PiGo
      this.liveActors = this.liveActors + newRunner
    }
  }
}

abstract class PiActor(val creationManager: ActorRef) extends Actor {

  def reportValue: Option[Pi] = None

  def forceReportStop: Receive = {
    case ForceReportStop => {
      this.creationManager ! ReportStop(this.reportValue)
    }
  }
}

// Runs a Pi process
class PiRunner(
  var chanMap: Map[Name, ActorRef],
  var proc: Pi,
  creationManager: ActorRef) extends PiActor(creationManager) {

  override def reportValue: Option[Pi] = Some(this.proc)

  def receive = ({
    case PiGo => this.proc match {
      case Par(p, q      ) => {
        this.creationManager ! MakeRunner(this.chanMap, q)
        this.proc = p
        self ! PiGo
      }
      case Rcv(r, c, b, p) => {
        this.chanMap(c) ! MsgRequestFromReceiver
        if(r) {
          this.creationManager ! MakeRunner(this.chanMap, this.proc)
        }
        this.proc = p
        context.become(({ case MsgChanToReceiver(channel) => {
          this.chanMap = this.chanMap.updated(b, channel)
          context.unbecome()
          self ! PiGo
        }}: Receive) orElse forceReportStop)
      }
      case Snd(c, m, p   ) => {
        this.chanMap(c) ! MsgSenderToChan(this.chanMap(m))
        this.proc = p
        context.become(({ case MsgConfirmToSender => {
          context.unbecome()
          self ! PiGo
        }}: Receive) orElse forceReportStop)
      }
      case New(c, p      ) => {
        this.creationManager ! MakeChannel
        context.become(({ case MakeChannelResponse(channel) => {
          this.chanMap = this.chanMap.updated(c, channel)
          this.proc = p
          context.unbecome()
          self ! PiGo
        }}: Receive) orElse forceReportStop)
      }
      case End             => this.creationManager ! ReportStop(None)
    }
  }: Receive) orElse forceReportStop
}

// Implements the behaviour of channels in pi calculus
class PiChannel(creationManager: ActorRef) extends PiActor(creationManager) {

  def deliver(sndr: ActorRef, rcvr: ActorRef, msg: ActorRef): Unit = {
        creationManager ! SendOccurred
        rcvr ! MsgChanToReceiver(msg)
        sndr ! MsgConfirmToSender
  }

  def receive = ({
    // If the receiver request comes before the sender delivery
    case MsgRequestFromReceiver => {
      val msgReceiver: ActorRef = sender
      context.become(({ case MsgSenderToChan(msg) => {
        val msgSender: ActorRef = sender
        this.deliver(msgSender, msgReceiver, msg)
        context.unbecome()
      }}: Receive) orElse forceReportStop)
    }
    // If the sender delivery comes before the receiver request
    case MsgSenderToChan(msg) => {
      val msgSender: ActorRef = sender
      context.become(({ case MsgRequestFromReceiver => {
        val msgReceiver: ActorRef = sender
        this.deliver(msgSender, msgReceiver, msg)
        context.unbecome()
      }}: Receive) orElse forceReportStop)
    }
  }: Receive) orElse forceReportStop
}

// Top class for messages sent in this implementation
sealed abstract class PiImplMessage

// Queries sent by PiRunners to PiChannels
sealed abstract class ChanQuery extends PiImplMessage
// Precursor to a MsgConfirmToSender ChanQueryResponse
case class  MsgSenderToChan(channel: ActorRef) extends ChanQuery
// Precursor to a MsgChanToReceiver ChanQueryResponse
case object MsgRequestFromReceiver             extends ChanQuery

// Responses sent by PiChannels to PiRunners
sealed abstract class ChanQueryResponse extends PiImplMessage
// Complements a MsgSenderToChan ChanQuery
case object MsgConfirmToSender                   extends ChanQueryResponse
// Complements a MsgRequestFromReceiver ChanQuery
case class  MsgChanToReceiver(channel: ActorRef) extends ChanQueryResponse

// Used to tell the PiCreationManager to create a new process or channel
sealed abstract class CreationRequest extends PiImplMessage
// Requests a new channel
case object MakeChannel extends CreationRequest
// Requests a new process
case class  MakeRunner(chanMap: Map[Name, ActorRef], p: Pi)
  extends CreationRequest
// Signals that a channel has been created to a process that requested a new one
case class MakeChannelResponse(channel: ActorRef) extends PiImplMessage

// Signalling object sent to PiRunners to tell them to do a computation step
case object PiGo extends PiImplMessage

// Tells PiActors to report their status to their creationManager which will
// then stop them
case object ForceReportStop extends PiImplMessage

// Used by PiActors to tell their creationManager their status before being
// stopped
case class ReportStop(op: Option[Pi]) extends PiImplMessage

// Used by the PiLauncher to give the references to the initial channels to the
// creationManager
case class SetLiveActors(actorset: Set[ActorRef]) extends PiImplMessage

// Tells the creationManager to test if the system has finished
case object CheckFinished extends PiImplMessage

// Tells the creationManager that a send has occurred since the last tick
case object SendOccurred extends PiImplMessage
