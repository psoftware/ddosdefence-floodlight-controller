#!/bin/bash
#sudo mn --topo single,3 --ipbase 10.0.0.0 --mac --controller remote,ip=127.0.0.1,port=6653,protocols=OpenFlow13 --pre mininet_conf
sudo python test_net.py
