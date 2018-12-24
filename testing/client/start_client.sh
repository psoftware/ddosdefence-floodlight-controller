#!/bin/bash

SCRIPTPATH="$( cd "$(dirname "$0")" ; pwd -P )"
HTTPSERVER_ADDRESS=$1;
HTTPSERVER_PORT=$2;

while true; do
	REQUEST_RESULT=$($SCRIPTPATH/gen_http_keepalive.sh | nc $HTTPSERVER_ADDRESS $HTTPSERVER_PORT -w 2);
	if [ $? -eq 1 ]; then
		echo "Connection dropped or reached timeout"
		continue;
	fi

	if [[ "$REQUEST_RESULT" == *"<html>"* ]]; then
		echo "Got Regular HTTP Response"
	else
		HTTPSERVER_ADDRESS=$(echo "$REQUEST_RESULT" | tail -n 1);
		echo "Forwarding to $HTTPSERVER_ADDRESS";
	fi
	sleep 2
done
