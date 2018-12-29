#!/usr/bin/python

"""
consoles.py: bring up a bunch of miniature consoles on a virtual network

This demo shows how to monitor a set of nodes by using
Node's monitor() and Tkinter's createfilehandler().

We monitor nodes in a couple of ways:

- First, each individual node is monitored, and its output is added
  to its console window

The consoles also support limited interaction:

- Pressing "return" in a console will send a command to it

- Pressing the console's title button will open up an xterm

Bob Lantz, April 2010

"""

import re

from Tkinter import Frame, Button, Label, Text, Scrollbar, Canvas, Wm, READABLE

from mininet.net import Mininet
from mininet.topo import SingleSwitchTopo,Topo
from mininet.node import OVSController,RemoteController
from mininet.log import setLogLevel
from mininet.term import makeTerms, cleanUpScreens
from mininet.util import quietRun

import time

class Console( Frame ):
	"A simple console on a host."

	def __init__( self, parent, net, node, height=10, width=32, title='Node' ):
		Frame.__init__( self, parent )

		self.net = net
		self.node = node
		self.prompt = node.name + '# '
		self.height, self.width, self.title = height, width, title

		# Initialize widget styles
		self.buttonStyle = { 'font': 'Monaco 7' }
		self.textStyle = {
			'font': 'Monaco 7',
			'bg': 'black',
			'fg': 'white',
			'width': self.width,
			'height': self.height,
			'relief': 'sunken',
			'insertbackground': 'green',
			'highlightcolor': 'green',
			'selectforeground': 'black',
			'selectbackground': 'green'
		}

		# Set up widgets
		self.text = self.makeWidgets( )
		self.bindEvents()
		self.sendCmd( 'export TERM=dumb' )

		self.outputHook = None

	def makeWidgets( self ):
		"Make a label, a text area, and a scroll bar."

		def newTerm( net=self.net, node=self.node, title=self.title ):
			"Pop up a new terminal window for a node."
			net.terms += makeTerms( [ node ], title )
		label = Button( self, text=self.node.name, command=newTerm,
						**self.buttonStyle )
		label.pack( side='top', fill='x' )
		text = Text( self, wrap='word', **self.textStyle )
		ybar = Scrollbar( self, orient='vertical', width=7,
						  command=text.yview )
		text.configure( yscrollcommand=ybar.set )
		text.pack( side='left', expand=True, fill='both' )
		ybar.pack( side='right', fill='y' )
		return text

	def bindEvents( self ):
		"Bind keyboard and file events."
		# The text widget handles regular key presses, but we
		# use special handlers for the following:
		self.text.bind( '<Return>', self.handleReturn )
		self.text.bind( '<Control-c>', self.handleInt )
		self.text.bind( '<KeyPress>', self.handleKey )
		# This is not well-documented, but it is the correct
		# way to trigger a file event handler from Tk's
		# event loop!
		self.tk.createfilehandler( self.node.stdout, READABLE,
								   self.handleReadable )

	# We're not a terminal (yet?), so we ignore the following
	# control characters other than [\b\n\r]
	ignoreChars = re.compile( r'[\x00-\x07\x09\x0b\x0c\x0e-\x1f]+' )

	def append( self, text ):
		"Append something to our text frame."
		text = self.ignoreChars.sub( '', text )
		self.text.insert( 'end', text )
		self.text.mark_set( 'insert', 'end' )
		self.text.see( 'insert' )
		outputHook = lambda x, y: True  # make pylint happier
		if self.outputHook:
			outputHook = self.outputHook
		outputHook( self, text )

	def handleKey( self, event ):
		"If it's an interactive command, send it to the node."
		char = event.char
		if self.node.waiting:
			self.node.write( char )

	def handleReturn( self, event ):
		"Handle a carriage return."
		cmd = self.text.get( 'insert linestart', 'insert lineend' )
		# Send it immediately, if "interactive" command
		if self.node.waiting:
			self.node.write( event.char )
			return
		# Otherwise send the whole line to the shell
		pos = cmd.find( self.prompt )
		if pos >= 0:
			cmd = cmd[ pos + len( self.prompt ): ]
		self.sendCmd( cmd )

	# Callback ignores event
	def handleInt( self, _event=None ):
		"Handle control-c."
		self.node.sendInt()

	def sendCmd( self, cmd ):
		"Send a command to our node."
		if not self.node.waiting:
			print("sendCmd(" + cmd + ")")
			self.node.sendCmd( "  " + cmd )

	def handleReadable( self, _fds, timeoutms=None ):
		"Handle file readable event."
		data = self.node.monitor( timeoutms )
		self.append( data )
		if not self.node.waiting:
			# Print prompt
			self.append( self.prompt )

	def waiting( self ):
		"Are we waiting for output?"
		return self.node.waiting

	def waitOutput( self ):
		"Wait for any remaining output."
		while self.node.waiting:
			# A bit of a trade-off here...
			self.handleReadable( self, timeoutms=1000)
			self.update()

	def clear( self ):
		"Clear all of our text."
		self.text.delete( '1.0', 'end' )

class ConsoleApp( Frame ):

	"Simple Tk consoles for Mininet."

	menuStyle = { 'font': 'Geneva 7 bold' }

	def __init__( self, net, controllerRESTApi, parent=None, width=4 ):
		Frame.__init__( self, parent )
		self.top = self.winfo_toplevel()
		self.top.title( 'Mininet' )
		self.net = net
		self.controllerRESTApi = controllerRESTApi
		self.menubar = self.createMenuBar()
		cframe = self.cframe = Frame( self )
		self.consoles = {}  # consoles themselves
		titles = {
			'hosts': 'Host',
			'switches': 'Switch',
			'controllers': 'Controller'
		}
		for name in titles:
			nodes = getattr( net, name )
			frame, consoles = self.createConsoles(
				cframe, nodes, width, titles[ name ] )
			self.consoles[ name ] = Object( frame=frame, consoles=consoles )
		self.selected = None
		self.select( 'hosts' )
		self.cframe.pack( expand=True, fill='both' )
		cleanUpScreens()
		# Close window gracefully
		Wm.wm_protocol( self.top, name='WM_DELETE_WINDOW', func=self.quit )

		self.pack( expand=True, fill='both' )

	def setOutputHook( self, fn=None, consoles=None ):
		"Register fn as output hook [on specific consoles.]"
		if consoles is None:
			consoles = self.consoles[ 'hosts' ].consoles
		for console in consoles:
			console.outputHook = fn

	def createConsoles( self, parent, nodes, width, title ):
		"Create a grid of consoles in a frame."
		f = Frame( parent )
		# Create consoles
		consoles = []
		index = 0
		for node in nodes:
			console = Console( f, self.net, node, title=title )
			consoles.append( console )
			row = index / width
			column = index % width
			console.grid( row=row, column=column, sticky='nsew' )
			index += 1
			f.rowconfigure( row, weight=1 )
			f.columnconfigure( column, weight=1 )
		return f, consoles

	def select( self, groupName ):
		"Select a group of consoles to display."
		if self.selected is not None:
			self.selected.frame.pack_forget()
		self.selected = self.consoles[ groupName ]
		self.selected.frame.pack( expand=True, fill='both' )

	def createMenuBar( self ):
		"Create and return a menu (really button) bar."
		f = Frame( self )
		buttons = [
			( 'Hosts', lambda: self.select( 'hosts' ) ),
			( 'Switches', lambda: self.select( 'switches' ) ),
			#( 'Controllers', lambda: self.select( 'controllers' ) ),
			( 'StartTest', self.startbots ),
			( 'EnableDDoSDefence', self.enableddosprotection),
			( 'GetFlows', self.getflows ),
			( 'Ping', self.ping ),
			( 'Interrupt', self.stop ),
			( 'Clear', self.clear ),
			( 'Quit', self.quit )
		]
		for name, cmd in buttons:
			b = Button( f, text=name, command=cmd, **self.menuStyle )
			b.pack( side='left' )
		f.pack( padx=4, pady=4, fill='x' )
		return f

	def clear( self ):
		"Clear selection."
		for console in self.selected.consoles:
			console.clear()

	def waiting( self, consoles=None ):
		"Are any of our hosts waiting for output?"
		if consoles is None:
			consoles = self.consoles[ 'hosts' ].consoles
		for console in consoles:
			if console.waiting():
				return True
		return False

	def ping( self ):
		"Tell each host to ping the next one."
		consoles = self.consoles[ 'hosts' ].consoles
		if self.waiting( consoles ):
			return
		count = len( consoles )
		i = 0
		for console in consoles:
			i = ( i + 1 ) % count
			ip = consoles[ i ].node.IP()
			console.sendCmd( 'ping 7.7.7.1' )

	def stop( self, wait=True ):
		"Interrupt all hosts."
		consoles = self.consoles[ 'hosts' ].consoles
		for console in consoles:
			console.handleInt()
		if wait:
			for console in consoles:
				console.waitOutput()
		self.setOutputHook( None )
		# Shut down any iperfs that might still be running
		quietRun( 'killall -9 iperf' )

	def quit( self ):
		"Stop everything and quit."
		self.stop( wait=False)
		Frame.quit( self )

	def getflows( self ):
		switches = self.consoles[ 'switches' ].consoles
		for console in switches:
			console.clear();
			console.sendCmd("ovs-dpctl dump-flows");

	def startbots( self ):
		"Start bots"
		consoles = self.consoles[ 'hosts' ].consoles

		# first start HTTPServer
		for console in consoles:
			if console.node.name.startswith("HTTPServer"):
				console.sendCmd("python server/MultithreadHTTPServer.py 7.7.7.1 80 \"<html>Standard page content</html>\"");

		# wait for server to start
		time.sleep(1);

		# then start bots and clients
		client_address = 1;
		for console in consoles:
			interface_name = console.node.name + "-eth0";
			if console.node.name.startswith("Bot"):
				console.sendCmd("./client/start_bot.sh 7.7.7.1 80");
				client_address += 1;
			elif console.node.name.startswith("Client"):
				console.sendCmd("./client/start_client.sh 7.7.7.1 80");
				client_address += 1;

	def enableddosprotection( self ):
		"Enable DDoS defence using Controller REST API and reset server to new address"
		# TODO: REST request to get this address
		result_code, new_address = controllerRESTApi.manage(enabled=True);
		consoles = self.consoles[ 'hosts' ].consoles
		for console in consoles:
			interface_name = console.node.name + "-eth0";
			if console.node.name.startswith("HTTPServer"):
				# kill current server
				console.handleInt();
				console.waitOutput();

				# kill forwarding server
				console.sendCmd("pkill -f 'python server/MultithreadHTTPServer.py*'");
				console.waitOutput();

				# set new address, start forwarding server on new address
				console.sendCmd(
					"ip addr flush dev " + interface_name + " && " +
					"ip addr add " + controllerRESTApi.oldAddress + "/24 dev "+ interface_name + " && " +
					"ip addr add " + new_address + "/24 dev "+ interface_name + " && " +
					"ip route add default dev " + interface_name + " && " +
					"python server/MultithreadHTTPServer.py " + controllerRESTApi.oldAddress + " 80 " + new_address + " &"
					);
				console.waitOutput();

				# start new normal http server
				console.sendCmd("python server/MultithreadHTTPServer.py " + new_address + " 80 \"<html>Standard page content</html>\"")

	def initdevices( self ):
		"Init bots, clients and server"
		consoles = self.consoles[ 'hosts' ].consoles
		client_address = 1;
		for console in consoles:
			console.waitOutput();
			interface_name = console.node.name + "-eth0";
			if console.node.name.startswith("Bot"):
				console.sendCmd("ip addr flush dev " + interface_name + " && " +
									"ip addr add 80.80.80." + str(client_address) + "/24 dev " + interface_name + " && "
									+ "ip route add default dev " + interface_name);
				client_address += 1;
			elif console.node.name.startswith("Client"):
				console.sendCmd("ip addr flush dev " + interface_name + " && " +
									"ip addr add 80.80.80." + str(client_address) + "/24 dev " + interface_name + " && "
									+ "ip route add default dev " + interface_name);
				client_address += 1;
			elif console.node.name.startswith("HTTPServer"):
				console.sendCmd("ip addr flush dev " + interface_name + " && " +
									"ip addr add 7.7.7.1/24 dev " + interface_name +" && "
									+ "ip route add default dev " + interface_name);


# Make it easier to construct and assign objects

def assign( obj, **kwargs ):
	"Set a bunch of fields in an object."
	obj.__dict__.update( kwargs )

import requests
class DDoSRESTInterface():
	def __init__(self):
		self.currentAddress = "7.7.7.1"
		self.oldAddress = "7.7.7.1"
	def init(self, port, threshold):
		url = 'http://127.0.0.1:8080/ddosdefence/init/json'
		data = '{"serviceport":' + str(port) + ',"addresses":["7.7.7.1","7.7.7.2","7.7.7.3","7.7.7.4"],"threshold":' + str(threshold) +'}'
		response = requests.post(url, data=data)
		print("DDoSRESTInterface.init(): got response code " + str(response.status_code) + " and response: " + response.text)
	def manage(self, enabled):
		url = 'http://127.0.0.1:8080/ddosdefence/manage/json'
		if enabled:
			data = '{"enabled":true}'
		else:
			data = '{"enabled":false}'
		response = requests.post(url, data=data)

		self.oldAddress = self.currentAddress
		self.currentAddress = response.text

		print("DDoSRESTInterface.manage(): got response code " + str(response.status_code) + " and response: " + response.text)
		return response.status_code, response.text;


class DDoSTestTopo(Topo):
	"Single switch connected to n hosts."
	def build(self, bots_n=2, client_n=2):
		dpid_base = 1;
		switch = self.addSwitch('switch', dpid=str(dpid_base));
		#print(str(dpid_base));
		dpid_base += 1;

		# HTTP Server
		host = self.addHost('HTTPServer', dpid=str(dpid_base));
		self.addLink(host, switch);
		dpid_base += 1;

		# Python's range(N) generates 0..N-1
		for h in range(bots_n):
			#print(str(dpid_base+h));
			host = self.addHost('Bot%d' % (h + 1), dpid=str(dpid_base+h))
			self.addLink(host, switch)

		dpid_base += bots_n;
		for h in range(client_n):
			#print(str(dpid_base+h));
			host = self.addHost('Client%d' % (h + 1), dpid=str(dpid_base+h))
			self.addLink(host, switch)

class Object( object ):
	"Generic object you can stuff junk into."
	def __init__( self, **kwargs ):
		assign( self, **kwargs )


if __name__ == '__main__':
	setLogLevel( 'info' )
	contr = RemoteController( 'DDoSDefence-controller', ip='127.0.0.1', port=6653 )
	#network = TreeNet( depth=2, fanout=4, controller=contr )
	network = Mininet( topo=DDoSTestTopo(bots_n=8, client_n=3), controller=contr)
	network.start()

	# init controller
	controllerRESTApi = DDoSRESTInterface();
	controllerRESTApi.init(port=80, threshold=10);

	app = ConsoleApp( network, controllerRESTApi, width=4 )
	app.initdevices()
	app.mainloop()
	network.stop()
