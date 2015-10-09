package main

import akka.actor._
import syntax._
import interpreter._

object Main extends App {
  val piLauncher: PiLauncher =
    new PiLauncher(Par(
      New(Name(4),Snd(Name(1), Name(2), Snd(Name(1), Name(2), End))),
      Rcv(false, Name(1), Name(3), Rcv(false, Name(1), Name(2), End))))
}
