#!/usr/bin/env python
# coding: utf-8
from tornado.conn import SockJSConnection
import logging,json
from channel_mgr.sortingstation import SortingStation

logger = logging.getLogger()

class SockJSConnectionHandler(SockJSConnection):
    def on_open(self, info):
        logger.debug("New connection opened.")
        # no messenger object yet, client needs issue CONNECT command first
        self.messenger = None
        self.dispatchers = {
            'CONNECT': self.handle_connect,
            'SUBSCRIBE': self.handle_subscribe,
            'SENDTOCHANEL':self.handle_sendto_chanel
        }

    def on_message(self, msg):
        logger.debug("Got message: %s" % msg)
        self.process_message(msg)

    def on_close(self):
        if self.connected:
            self.messenger.unregister_user(self)
            self.messenger = None

        logger.debug("User %s has disconnected."
            % getattr(self, "userid", None))

    def force_disconnect(self):
        self.close(9002, "Server closed the connection (intentionally).")

    def process_message(self, msg):
        """
        We assume that every client message comes in following format:
        COMMAND argument1[:argument2[:argumentX]]
        """
        try:
            if msg.startswith("CONNECT") == True:
                self.dispatchers["CONNECT"](msg[7:])
            elif msg.startswith("SUBSCRIBE") == True:
                self.dispatchers["SUBSCRIBE"](msg[9:])
            elif msg.startswith("SENDTOCHANEL") == True:
                self.dispatchers["SENDTOCHANEL"](msg[12:])
        #tokens = msg.split(" ")
        #try:
        #    self.dispatchers[tokens[0]](tokens[1])
        except (KeyError, IndexError):
            logger.warning("Received invalid message: %s." % msg)

    def handle_connect(self, args):
        if self.connected:
            logger.warning("User already connected.")
            return

        try:
            self.userid, self.apikey = args.split(":")
        except ValueError:
            logger.warning("Invalid message syntax.")
            return

        #get singleton instance of SortingStation
        ss = SortingStation.instance()
        # get and store the messenger object for given apikey
        self.messenger = ss.get_messenger_by_apikey(self.apikey)

        if self.messenger:
            self.messenger.register_user(self)
        else:
            self.close(9000, "Invalid API key.")
    
    
    def handle_subscribe(self, args):
        if not self.connected:
            logger.warning("User not connected.")

            # close the connection, the user issues commands in a wrong order
            self.close(9001, "Subscribing before connecting.")
            return

        channels = args.split(":")
        if len(channels):
            for channel in channels:
                self.channel_id = channel
                self.messenger.subscribe_user_to_channel(self, channel)

    def handle_sendto_chanel(self,args):
        try:
            self.msg_body = args
        except ValueError:
            logger.warning("Invalid message syntax.")
            return

        if self.messenger:
            #self.messenger.send_to_channel(self.channel_id,self.msg_body)
            self.messenger.send_to_chanel_parteners(self.userid,self.channel_id,self.msg_body)
        else:
            self.close(9000, "Invalid API key.")

    def close(self, code=3000, message="Go away!"):
        self.session.close(code, message)

    @property
    def connected(self):
        return bool(self.messenger)
