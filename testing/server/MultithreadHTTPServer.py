from BaseHTTPServer import HTTPServer, BaseHTTPRequestHandler
from SocketServer import ThreadingMixIn
import threading
import sys

class Handler(BaseHTTPRequestHandler):
    fixedresponse = "Undefined response"

    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        message =  threading.currentThread().getName()
        self.wfile.write(self.fixedresponse)
        self.wfile.write('\n')
        return

        
class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    """Handle requests in a separate thread."""

if __name__ == '__main__':
    server = ThreadedHTTPServer((sys.argv[1], int(sys.argv[2])), Handler)
    Handler.fixedresponse = sys.argv[3]
    print 'Starting server, use <Ctrl-C> to stop'
    server.serve_forever()
