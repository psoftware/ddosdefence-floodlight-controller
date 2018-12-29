#!/bin/bash

SCRIPTPATH="$( cd "$(dirname "$0")" ; pwd -P )"

while true; do
	REQUEST_RESULT=$($SCRIPTPATH/gen_http_keepalive.sh | nc $1 $2 -w 4);
	if [ $? -eq 1 ]; then
		echo "Connection dropped or reached timeout"
		continue;
	fi

	if [[ "$REQUEST_RESULT" == *"<html>"* ]]; then
		echo "Got Regular HTTP Response"
	else
		echo "Got Forwarding HTTP Response. I can't forward! (I'm stupid)"
	fi
done
