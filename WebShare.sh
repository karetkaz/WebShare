#!/bin/sh
## usage java -jar WebShare.jar [arguments] <shared_path>
## -repo <url>: use as proxy, with write enabled caches the responses from server.
## -host <string>: override default: -host 'http://localhost'.
## -port <number>: override default: -port '8090'.
## -auth <string>: require username and password. ex: -auth 'UserName:pass123!'.
## -log <file>: output logs to the given file and console.
## -n <number>: override simultaneous requests: -n '256'.
## -write: enable uploading, and deleting files from the shared directory.

#~ change working directory to this files parent
cd "$(dirname "$(readlink -f "$0")")"
PATH=$JAVA_HOME/bin:$PATH

if [ ! -e WebShare.jar ]
then
	mkdir "out"
	echo compiling server ...
	javac -cp "lib/sceye-fi.jar" -d out src/kmz/webshare/*.java
	cd out
	jar xf "../lib/sceye-fi.jar"
	cd ..
	jar cvfe "WebShare.jar" kmz.webshare.WebShare -C out/ . mime.map FileList.html
fi

#~ java -jar WebShare.jar -write "$@"
java -jar WebShare.jar "$@"

## using as a file caching proxy: all visited pages will be saved locally
#~ REPO="https://qmlbook.github.io"
#~ java -jar WebShare.jar -repo $REPO -write './qmlbook.github.io'
