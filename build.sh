#!/bin/sh
set -x
wget https://github.com/coursier/sbt-launcher/releases/download/v1.2.14/csbt
chmod +x csbt
export COURSIER_SBT_LAUNCHER_ADD_PLUGIN=true
echo "compile
compile
compile
compile
compile
compile
compile
compile
compile
compile
compile
compile
compile
compile
compile
exit" | ./csbt
