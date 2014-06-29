#!/usr/bin/env python
# coding: utf-8

import tornado.web
import re
from utils import *
from channel_mgr.sortingstation import SortingStation 

LOCK = threading.RLock()

class IndexHandler(tornado.web.RequestHandler):
    def get(self):
        error_messages = []
        base_url = self.request.uri
        user_agent = self.request.headers['User-Agent']
        #chanel = sanitize(self.get_argument("chanel",""))
        channel = self.get_argument("channel","")
        user_id = self.get_argument("user_id","")
        partner_id = self.get_argument("partner_id", "")
        if channel == "":
            channel = generate_random(8)
            redirect_url = "/?channel="+channel
            self.redirect(redirect_url)
        stun_server = get_default_stun_server(user_agent)
        audio_send_codec = get_preferred_audio_send_codec(user_agent)
        audio_receive_codec = get_preferred_audio_receive_codec()
        stereo = 'false'
        dtls = ""
        dscp = ""
        ipv6 = ""
        debug = self.get_argument('debug',None)
        if debug == 'loopback':
            dtls = 'false'
        with LOCK:
            channel_ = SortingStation.instance().get_messenger_by_apikey("cnmooc").is_channel_created(channel)
            if not channel_:
                user = user_id
                initiator = 0
                push_msg={
                    'live_id':channel,
                    'partner_id':user_id
                }
                SortingStation.instance().publish_message(str(partner_id),json.dumps(push_msg))
            else:
                SortingStation.instance().get_messenger_by_apikey("cnmooc").kickoff_if_logged(channel,user_id)
                user_count = SortingStation.instance().get_messenger_by_apikey("cnmooc").get_channel_user_count(channel)
                if user_count == 1:
                    user = user_id
                    initiator = 1
                else:
                    return self.render("full.html")
            '''if not channel_ and debug != "full":
                #user = generate_random(8)
                if debug != 'loopback':
                    initiator = 0
                    SortingStation.instance().publish_message(partner_id,partner_id)
                else:
                    initiator = 1
            elif channel_ and debug != 'full':
                user_count = SortingStation.instance().get_messenger_by_apikey("cnmooc").get_channel_user_count(channel)
                if user_count == 1:
                    user = generate_random(8)
                    initiator = 1
                else:
                    
                    return self.render("full.html")'''
                
        turn_server = 'lsxu@211.136.105.167'
        turn_url = 'lsxu@211.136.105.167'
        audio = None
        video = None
        pc_config = make_pc_config(stun_server, turn_server, "lsxu")
        print pc_config
        pc_constraints = make_pc_constraints(dtls, dscp, ipv6)
        offer_constraints = make_offer_constraints()
        media_constraints = make_media_stream_constraints(audio, video)
        template_values = {'error_messages': error_messages,
                       'me': str(user),
                       'initiator': initiator,
                       'channel':str(channel),
                       'pc_config': json.dumps(pc_config),
                       'pc_constraints': json.dumps(pc_constraints),
                       'offer_constraints': json.dumps(offer_constraints),
                       'media_constraints': json.dumps(media_constraints),
                       'turn_url': turn_url,
                       'stereo': stereo,
                       'audio_send_codec': audio_send_codec,
                       'audio_receive_codec': audio_receive_codec
                       }
        self.render("index.html",params=template_values);