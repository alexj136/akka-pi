package syntax

case class Name(val id: Int) { override def toString = this.id.toString }

sealed abstract class Pi {
  def free: Set[Name] = this match {
    case Par(p, q      ) => p.free union p.free
    case Rcv(_, c, b, p) => (p.free - b) + c
    case Snd(c, m, p   ) => (p.free + m) + c
    case New(c, p      ) => p.free - c
    case End             => Set.empty
  }
  override def toString: String = this match {
    case Par(p, q      ) => s"$p | $q"
    case Rcv(r, c, b, p) => s"${if (r) "!" else ""}$c($b).$p"
    case Snd(c, m, p   ) => s"$c<$m>.$p"
    case New(c, p      ) => s"(nu $c)"
    case End             => "0"
  }
}
case class  Par(p: Pi      , q: Pi                     ) extends Pi
case class  Rcv(r: Boolean , c: Name , b: Name , p: Pi ) extends Pi
case class  Snd(c: Name    , m: Name , p: Pi           ) extends Pi
case class  New(c: Name    , p: Pi                     ) extends Pi
case object End                                          extends Pi
