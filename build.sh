#!/bin/sh
set -x
curl -L -o csbt https://github.com/coursier/sbt-launcher/releases/download/v1.2.14/csbt
chmod +x csbt
export COURSIER_SBT_LAUNCHER_ADD_PLUGIN=true
./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile || \
	 ./csbt compile
