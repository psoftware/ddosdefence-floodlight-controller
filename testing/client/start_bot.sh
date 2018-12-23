#!/bin/bash

SCRIPTPATH="$( cd "$(dirname "$0")" ; pwd -P )"

while true; do
	$SCRIPTPATH/gen_http_keepalive.sh | nc $1 $2 -w 2
	echo "connection dropped. Retrying..."
done
