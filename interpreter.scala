package interpreter

import akka.actor._
import syntax._

class PiRunner(var chanMap: Map[Name, Channel], var p: Pi) extends Actor {

  def this(p: Pi) = this(Map.empty, p match {
    case Par(q, r   ) => ???
    case Rcv(c, b, p) => ???
    case Srv(c, b, p) => ???
    case Snd(c, m, p) => ???
    case New(c, p   ) => ???
    case End          => ???
  })

  def receive: Receive = {
    case Name(id) => this.p match {
      case _ => ???
    }
  }
}

sealed abstract class ChanQuery
case class  ChanPut(c: Name) extends ChanQuery
case object ChanGet          extends ChanQuery

class Channel extends Actor {
  
  var cur_msg: Option[Name] = None

  def receive: Receive = {
    case ChanGet      if !cur_msg.isEmpty => {
      sender ! this.cur_msg.get
      this.cur_msg = None
      println("ChanGet handled")
    }
    case ChanPut(msg) if  cur_msg.isEmpty => {
      this.cur_msg = Some(msg)
      println(s"ChanPut of ${msg.id} handled")
    }
  }
}

class Dummy(a: ActorRef) extends Actor {
  def receive: Receive = {
    case Name(0) => a ! ChanGet
    case Name(1) => println("Balalau")
  }
}
