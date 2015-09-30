package interpreter

import akka.actor._
import syntax._

class PiRunner(chanMap: Map[Name, Channel]) extends Actor {

  def receive: Receive = {
    case Name(id) => println("lol")
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
