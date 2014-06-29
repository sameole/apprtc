#!/usr/bin/python2.4
#
# Copyright 2011 Google Inc. All Rights Reserved.

"""WebRTC Demo

This module demonstrates the WebRTC API by implementing a simple video chat app.
"""
import logging
import os
import optparse
from config import settings
from config.urls import url_handler_dict
from channel_mgr.sortingstation import SortingStation
from sockjs.sockjs_handler import SockJSConnectionHandler
from sockjs.tornado.router import SockJSRouter

import tornado.web
# Lock for syncing DB operation in concurrent requests handling.
# TODO(brave): keeping working on improving performance with thread syncing.
# One possible method for near future is to reduce the message caching.
logger = logging.getLogger()

def run_server():
    # configure logging level
    if settings.VERBOSE:
        logger.setLevel(logging.DEBUG)
    else:
        logger.setLevel(logging.INFO)

    sockjs_router = SockJSRouter(SockJSConnectionHandler, "/connect")
    global url_handler_dict
    url_handler_dict += sockjs_router.urls

    app_settings = dict(
        template_path=os.path.join(os.path.dirname(__file__), "templates"),
        static_path=os.path.join(os.path.dirname(__file__), "static"),
        debug=True,
        cookie_secret="61oETzKXQAGaYdkL5gEmGeJJFuYh7EQnp2XdTP1o/Vo="
        )
    application = tornado.web.Application(url_handler_dict, **app_settings)

    ss = SortingStation()

    # Single-client only at the moment.
    ss.create_messenger(settings.APIKEY, settings.APISECRET)
    logger.info("Starting Thunderpush server at %s:%d",
        settings.HOST, settings.PORT)

    #application.listen(settings.PORT, settings.HOST)
    application.listen(settings.PORT)
    print settings.HOST,settings.PORT

    try:
        tornado.ioloop.IOLoop.instance().start()
    except KeyboardInterrupt:
        logger.info("Shutting down...")

def parse_arguments(opts, args):
    for optname in ["PORT", "HOST", "VERBOSE", "DEBUG"]:
        value = getattr(opts, optname, None)

        if not value is None:
            setattr(settings, optname, value)

    settings.APIKEY = args[0]
    settings.APISECRET = args[1]


def validate_arguments(parser, opts, args):
    if len(args) != 2:
        parser.error("incorrect number of arguments")
        
def main():
    usage = "usage: %prog [options] apikey apisecret"
    parser = optparse.OptionParser(usage=usage)

    parser.add_option('-p', '--port',
        default=settings.PORT,
        help='binds server to custom port',
        action="store", type="int", dest="PORT")

    parser.add_option('-H', '--host',
        default=settings.HOST,
        help='binds server to custom address',
        action="store", type="string", dest="HOST")

    parser.add_option('-v', '--verbose',
        default=settings.VERBOSE,
        help='verbose mode',
        action="store_true", dest="VERBOSE")

    parser.add_option('-d', '--debug',
        default=settings.DEBUG,
        help='debug mode (useful for development)',
        action="store_true", dest="DEBUG")

    opts, args = parser.parse_args()

    validate_arguments(parser, opts, args)
    parse_arguments(opts, args)
    run_server()
    
if __name__ == "__main__":
    main()    