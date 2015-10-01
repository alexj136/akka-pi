package syntax

case class Name(val id: Int)

sealed abstract class Pi {
  def free: Set[Name] = this match {
    case Par(p, q   ) => (p free) union (p free)
    case Rcv(c, b, p) => ((p free) - b) + c
    case Srv(c, b, p) => ((p free) - b) + c
    case Snd(c, m, p) => ((p free) + m) + c
    case New(c, p   ) => (p free) - c
    case End          => Set empty
  }
}

case class  Par(p: Pi  , q: Pi         ) extends Pi
case class  Rcv(c: Name, b: Name, p: Pi) extends Pi
case class  Srv(c: Name, b: Name, p: Pi) extends Pi
case class  Snd(c: Name, m: Name, p: Pi) extends Pi
case class  New(c: Name, p: Pi         ) extends Pi
case object End                          extends Pi
