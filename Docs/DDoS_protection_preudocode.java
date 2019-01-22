function handle_PacketIn(under_ddos_attack, threshold, p) {

    /*for server to client packets*/
    //if (p.srcIP == D || (under_ddos_attack && p.srcIP == D_new)) {
    if(isServerAddress(p.srcIP)){
        match.srcIP = p.srcIP
        match.dstIP = p.destIP
        sendFlowAddCommandToSwitch(match)
        return
    }

    /* only executed for client to server packets*/
    if (under_ddos_attack) { /* only executed under attack */
        if (p.srcIP not in flowCount) {
            flowCount[p.srcIP] = p.tcpSrcPort
        }
        else if (p.tcpSrcPort not in flowCount[p.srcIP]) {
            flowCount[p.srcIP].add(p.tcpSrcPort)
        }
    }

    if (length(flowCount[p.srcIP]) > threshold) {
        match.srcIP = p.srcIP
        match.dstIP = p.dstIP
        action = drop
        /* bot */
    }
    else { /* flow count is still small or server is not under attack */
        match.srcIP = p.srcIP
        match.dstIP = p.dstIP
        match.tcpSrcPort = p.tcpSrcPort
        action = forward

        if (under_ddos_attack && packet.dstIP == D_new) {

            match.srcIP = p.srcIP
            match.dstIP = D
            sendFlowDeletionCommandToSwitch(match)
            match.srcIP = D
            match.dstIP = p.srcIP
            sendFlowDeletionCommandToSwitch(match)
            
            flowCount[p.srcIP].clear()
        } /* a redirected flow arrives */
    }
    sendFlowAddCommandToSwitch(match, action)
}
