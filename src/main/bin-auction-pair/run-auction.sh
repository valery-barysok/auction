#!/bin/bash

base=`pwd`

if [ ! -f $BARATINE_HOME/lib/baratine.jar ]; then
  echo "BARATINE_HOME '$BARATINE_HOME' does not point to a baratine installation";
  exit 1;
fi;

BARATINE_DATA_DIR=/tmp/baratine
BARATINE_CONF=src/main/bin-auction-pair/conf.cf
BARATINE_ARGS="--data-dir $BARATINE_DATA_DIR --conf $BARATINE_CONF"

killall -9 java

$BARATINE_HOME/bin/baratine shutdown $BARATINE_ARGS

rm -rf $BARATINE_DATA_DIR
sync
mvn -Dmaven.test.skip=true -P local clean package

exit_code=$?

if [ $exit_code -ne 0 ]; then
  echo "mvn package failed"
  exit $exit_code
fi

cp target/auction-*.bar auction.bar

mvn dependency:copy -Dartifact=com.caucho:lucene-plugin-service:1.0-SNAPSHOT:bar -Dmdep.stripVersion=true -o -DoutputDirectory=$base

$BARATINE_HOME/bin/baratine start $BARATINE_ARGS --server web
$BARATINE_HOME/bin/baratine start $BARATINE_ARGS --server user

$BARATINE_HOME/bin/baratine start $BARATINE_ARGS --server auction-0
$BARATINE_HOME/bin/baratine start $BARATINE_ARGS --server auction-1

#$BARATINE_HOME/bin/baratine start $BARATINE_ARGS --server auction-0

$BARATINE_HOME/bin/baratine start $BARATINE_ARGS --server audit
$BARATINE_HOME/bin/baratine start $BARATINE_ARGS --server lucene
$BARATINE_HOME/bin/baratine deploy $BARATINE_ARGS lucene-plugin-service.bar --port 8085
$BARATINE_HOME/bin/baratine deploy $BARATINE_ARGS auction.bar --port 8080

sleep 5

echo "Create User ..."
$BARATINE_HOME/bin/baratine jamp-query $BARATINE_ARGS --pod web --port 8080 /auction-session/foo createUser user pass

#echo "Authenticate User ..."
#$BARATINE_HOME/bin/baratine jamp-query $BARATINE_ARGS --pod web /auction-session/foo login user pass

#$BARATINE_HOME/bin/baratine cat $BARATINE_ARGS /proc/services

