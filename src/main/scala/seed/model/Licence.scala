package seed.model

// Adapted from https://github.com/sbt/sbt-license-report/blob/ffff4e456e153f7149df5a58302e9eb96b5a4744/src/main/scala/com/typesafe/sbt/license/Licence.scala#L1
case class Licence(id: String, name: String, url: String)
object Licence {
  val GPL2 = Licence(
    "gpl:2.0",
    "GNU General Public License (GPL), Version 2.0",
    "http://opensource.org/licenses/GPL-2.0"
  )
  val GPL3 = Licence(
    "gpl:3.0",
    "GNU General Public License (GPL), Version 3.0",
    "http://opensource.org/licenses/GPL-3.0"
  )
  val LGPL2 = Licence(
    "lgpl:2.1",
    "GNU Library or \"Lesser\" General Public License (LGPL), Version 2.1",
    "http://opensource.org/licenses/LGPL-2.1"
  )
  val LGPL3 = Licence(
    "lgpl:3.0",
    "GNU Library or \"Lesser\" General Public License (LGPL), Version 3.0",
    "http://opensource.org/licenses/LGPL-3.0"
  )
  val CDDL = Licence(
    "cddl:1.0",
    "Common Development and Distribution License (CDDL), Version 1.0",
    "http://opensource.org/licenses/CDDL-1.0"
  )
  val CDDL_GPL = Licence(
    "cddl+gpl",
    "CDDL + GPLv2 License",
    "https://glassfish.dev.java.net/nonav/public/CDDL+GPL.html"
  )
  val Apache2 = Licence(
    "apache:2.0",
    "Apache License, Version 2.0",
    "http://www.apache.org/licenses/LICENSE-2.0"
  )
  val BSD2 = Licence(
    "bsd:2",
    "BSD 2-Clause",
    "http://opensource.org/licenses/BSD-2-Clause"
  )
  val BSD3 = Licence(
    "bsd:3",
    "BSD 3-Clause",
    "http://opensource.org/licenses/BSD-3-Clause"
  )
  val MIT = Licence("mit", "MIT License", "http://opensource.org/licenses/MIT")
  val EPL = Licence(
    "epl:1.0",
    "Eclipse Public License, Version 1.0",
    "https://www.eclipse.org/legal/epl-v10.html"
  )
  val EDL = Licence(
    "ecl:1.0",
    "Eclipse Distribution License, Version 1.0",
    "http://www.eclipse.org/org/documents/edl-v10.php"
  )
  val MPL = Licence(
    "mpl:2.0",
    "Mozilla Public License, Version 2.0",
    "https://www.mozilla.org/MPL/2.0/"
  )

  val All = List(
    GPL2,
    GPL3,
    LGPL2,
    LGPL3,
    CDDL,
    CDDL_GPL,
    Apache2,
    BSD2,
    BSD3,
    MIT,
    EPL,
    EDL,
    MPL
  )
}
