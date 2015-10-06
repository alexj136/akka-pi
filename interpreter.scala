package interpreter

import akka.actor._
import syntax._

class PiLauncher(p: Pi) {

  val (system: ActorSystem, creationManager: ActorRef) = {

    val sys: ActorSystem = ActorSystem("PiLauncher")
    val initChanMap: Map[Name, ActorRef] =
      (p.free map { case n => (n, sys actorOf Props[Channel]) }).toMap
    val creationManager: ActorRef = sys actorOf Props[PiCreationManager]
    creationManager ! MakeRunner(initChanMap, p)

    (sys, creationManager)
  }
}

// Serves as parent actor for other actors. Keeps track of channels and runners.
// Can be queried for deadlock detection.
class PiCreationManager extends Actor {

  var nextID: Int = 0
  var channels: List[ActorRef] = Nil
  var runners: Map[Int, ActorRef] = Map.empty

  def receive: Receive = {
    case MakeChannel => {
      val channel: ActorRef = context.actorOf(Props[Channel])
      sender ! MakeChannelResponse(channel)
      this.channels = channel :: this.channels
    }
    case MakeRunner(chanMap, p) => {
      val runner: ActorRef =
        context.actorOf(Props(classOf[PiRunner], chanMap, p, this.nextID, self))
      runner ! PiGo
      this.runners = this.runners.updated(this.nextID, runner)
      this.nextID = this.nextID + 1
    }
    case CreationManagerCheck => {
      sender ! CreationManagerStatus(channels, runners)
    }
    case Terminate => {
      var procs: List[Pi] = Nil
      for (val runner <- (this.runners.toList map _._2)) {
        runner ! RunnerCheck
        context.become({ case RunnerStatus(p) =>
          procs = p :: procs
          context.unbecome()
        }})
        runner ! PoisonPill
      }
      this.channels map _.!(PoisonPill)
      sender ! Terminated
      self ! PoisonPill
    }
  }
}

class PiTerminationWatcher(creationManager: ActorRef) extends Actor {

  val pChannels: Option[List[ActorRef]] = None
  val pRunners: Option[Map[Int, ActorRef]] = None

  def receive: Receive = {
    case CreationManagerCheck => {
      creationManager ! CreationManagerCheck
      context.become({ case CreationManagerStatus(channels, runners) => {
        if (Some(channels) == this.pChannels && Some(runners) == this.pRunners) {
          creationManager ! Terminate
          context.become({ case Terminated => {
            context.unbecome()
          }})
        }
        else {
          this.pChannels = Some(channels)
          this.pRunners = Some(runners)
        }
        context.unbecome()
      }})
    }
  }
}

// Runs a Pi process
class PiRunner(
  var chanMap: Map[Name, ActorRef],
  var proc: Pi,
  val id: Int,
  val creationManager: ActorRef) extends Actor {

  def receive: Receive = {
    case RunnerCheck => {
      sender ! RunnerStatus(this.proc)
    }
    case PiGo => this.proc match {
      case Par(p, q      ) => {
        this.creationManager ! MakeRunner(this.chanMap, q)
        this.proc = p
        self ! PiGo
      }
      case Rcv(r, c, b, p) => {
        this.chanMap(c) ! ChanAsk
        this.proc = p
        if(r) {
          this.creationManager ! MakeRunner(this.chanMap, this.proc)
        }
        context.become({ case ChanGet(channel) => {
          this.chanMap = this.chanMap.updated(b, channel)
          context.unbecome()
        }})
        self ! PiGo
      }
      case Snd(c, m, p   ) => {
        this.chanMap(c) ! ChanGive(this.chanMap(m))
        this.proc = p
        context.become({ case ChanTaken => {
          context.unbecome()
        }})
        self ! PiGo
      }
      case New(c, p      ) => {
        this.creationManager ! MakeChannel
        context.become({ case MakeChannelResponse(channel) => {
          this.chanMap = this.chanMap.updated(c, channel)
          context.unbecome()
        }})
        this.proc = p
        self ! PiGo
      }
      case End             => {
        println("process ending")
        self ! PoisonPill
      }
    }
  }
}

// Implements the behaviour of channels in pi calculus
class Channel extends Actor {
  
  var curMsgAndSender: Option[(ActorRef, ActorRef)] = None
  def curMsg: Option[ActorRef] = curMsgAndSender map (_._1)
  def curSender: Option[ActorRef] = curMsgAndSender map (_._2)

  def holding: Receive = {
    case ChanAsk => {
      println("ChanAsk at channel")
      sender ! ChanGet(this.curMsg.get)
      this.curSender.get ! ChanTaken
      this.curMsgAndSender = None
      context.unbecome()
    }
  }

  def receive: Receive = {
    case ChanGive(msg) => {
      println("ChanGive at channel")
      this.curMsgAndSender = Some((msg, sender))
      context become holding
    }
  }
}

// Top class for messages sent in this implementation
sealed abstract class PiImplMessage

// Queries sent by PiRunners to Channels
sealed abstract class ChanQuery extends PiImplMessage
// Precursor to a ChanTaken ChanQueryResponse
case class  ChanGive(channel: ActorRef) extends ChanQuery
// Precursor to a ChanGet ChanQueryResponse
case object ChanAsk                     extends ChanQuery

// Responses sent by Channels to PiRunners
sealed abstract class ChanQueryResponse extends PiImplMessage
// Complements a ChanGive ChanQuery
case object ChanTaken                   extends ChanQueryResponse
// Complements a ChanAsk ChanQuery
case class  ChanGet(channel: ActorRef)  extends ChanQueryResponse

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

// Signals to the PiTerminationWatcher that it should check if termination has
// occured
case object CreationManagerCheck extends PiImplMessage

// Sent by the PiCreationManager to the PiTerminationWatcher to indicate the
// execution status
case class CreationManagerStatus(
  channels: List[ActorRef],
  runners: Map[Int, ActorRef]) extends PiImplMessage

// Sent by PiTerminationWatcher to PiCreationManager to instruct it to terminate
case object Terminate extends PiImplMessage

// Complement to the previous message
case object Terminated extends PiImplMessage

// Sent by the PiCreationManager to query the execution status of the process
// contained in a PiRunner
case object RunnerCheck extends PiImplMessage

// Sent by a PiRunner to inform the PiCreationManager of the status of the
// contained process
case object RunnerStatus(p: Pi) extends PiImplMessage
