#!/bin/bash

STARTING_SERVER_ADDRESS=$1
SERVER_PORT=$2

API_ADDRESS="http://127.0.0.1:8080/ddosdefence/defence/json"
API_ENABLE_DEFENCE_PAYLOAD="{\"enabled\":true}"
CLI_NAME="http server> "

START_SERVER_COMMAND="python httpserver_nothreading.py $1 $2"
START_FORWARDED_SERVER_COMMAND=""

function start_server {
	$START_SERVER_COMMAND &
	local new_pid=$!
	return $new_pid
}

function start_forwarding_server {
	$START_SERVER_FORWARDED &
        local new_pid=$!
        return $new_pid
}

regular_server_pid=0;
regular_server_address=$SERVER_ADDRESS;
forwarded_server_pid=0;
forwarded_server_address=0;

function notify_attack {
#	1a) set request to API to enable protection and get address D' from API
	forwarded_server_address=regular_server_address;
	regular_server_address=$(curl -X POST $API_ADDRESS -H "Content-Type: application/json" -d '$API_ENABLE_DEFENCE_PAYLOAD');
	echo "Got new address $regular_server_address"

#	2a) stop http server
	kill $regular_server_pid;
#	2b) stop http forwarding server
	if [ forwarded_server_pid -ne 0 ]; then
		kill forwarded_server_pid;
	fi

#	3a) start http server on D'
	start_server $regular_server_address $SERVER_PORT
	regular_server_address=$?;

#	4a) start http forwarding server on D
	start_forwarding_server $forwarded_server_address $SERVER_PORT
	forwarded_server_address=$?;
}

# 1) Start http server on $1:$2
start_server $STARTING_SERVER_ADDRESS $SERVER_PORT
regular_server_pid=$?;
echo $regular_server_pid

# 2) print console> wait for ddos attack command
echo -n "$CLI_NAME"
while read -r command
do
	if [ "$command" == "exit" ]; then
		break;
	elif [ "$command" == "notify-attack" ]; then
		notify_attack;
	fi
	echo -n "$CLI_NAME";
done
