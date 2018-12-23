#!/bin/bash

#for i in $(seq 1 10); do
while true; do
	echo -e "GET / HTTP/1.1\r\n" 2> /dev/null;
	if [ $? -ne 0 ]; then
		exit;
	fi
	sleep 1;
done
