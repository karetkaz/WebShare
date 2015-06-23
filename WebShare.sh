#!/bin/sh
## arguments for server.jar
## -host <string>: override default host: `http://localhost`
## -port <number>: override default port: 8090
## -n <number>: spevify how many simultaneous requests can be done. default is 256
## -auth <string>: require username and password. ex: -auth User:secret1
## -write: enable uploading, and deleting.

#~ change working directory to this files parent
cd "$(dirname "$(readlink -f "$0")")"
WORKDIR=$1
PATH=$JAVA_HOME/bin:$PATH

if [ ! -e server.jar ]
then
	mkdir "out"
	echo compiling server ...
	javac -cp "lib/sceye-fi.jar" -d out src/*.java
	cd out
	jar xf "../lib/sceye-fi.jar"
	cd ..
	jar cvfe "server.jar" WebShare "lib/sceye-fi.jar" -C out/ .
fi


java -jar server.jar -port 8080 "$WORKDIR"

#~ use as a file caching proxy: save locally all visited pages will
#REPO="https://qmlbook.github.io"
#java -cp server.jar HttpFileProxy -port 8080 -repo $REPO "$WORKDIR"
