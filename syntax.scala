package syntax

case class Name(val id: Int)

sealed abstract class Pi
case class  Par(p: Pi  , q: Pi         ) extends Pi
case class  Rcv(c: Name, b: Name, p: Pi) extends Pi
case class  Srv(c: Name, b: Name, p: Pi) extends Pi
case class  Snd(c: Name, m: Name, p: Pi) extends Pi
case class  New(c: Name, p: Pi         ) extends Pi
case object End                          extends Pi
