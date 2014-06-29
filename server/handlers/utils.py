#!/usr/bin/env python
# coding: utf-8
import random
import re
import json
import logging
import threading
import cgi

def generate_random(length):
    word = ''
    for _ in range(length):
        word += random.choice('0123456789')
    return word

def sanitize(key):
    return re.sub('[^a-zA-Z0-9\-]', '-', key)

def make_client_id(room, user):
    return room.key().id_or_name() + '/' + user

def get_default_stun_server(user_agent):
    default_stun_server = 'stun.l.google.com:19302'
    if 'Firefox' in user_agent:
        default_stun_server = 'stun.services.mozilla.com'
    return default_stun_server

def get_preferred_audio_receive_codec():
    return 'opus/48000'

def get_preferred_audio_send_codec(user_agent):
    # Empty string means no preference.
    preferred_audio_send_codec = ''
    # Prefer to send ISAC on Chrome for Android.
    if 'Android' in user_agent and 'Chrome' in user_agent:
        preferred_audio_send_codec = 'ISAC/16000'
    return preferred_audio_send_codec

def make_pc_config(stun_server, turn_server, ts_pwd):
    servers = []
    if turn_server:
        turn_config = 'turn:{0}'.format(turn_server)
        servers.append({'urls':turn_config, 'credential':ts_pwd})
    if stun_server:
        stun_config = 'stun:{0}'.format(stun_server)
        servers.append({'urls':stun_config})
    return {'iceServers':servers}

def make_loopback_answer(message):
    message = message.replace("\"offer\"", "\"answer\"")
    message = message.replace("a=ice-options:google-ice\\r\\n", "")
    return message


def make_media_track_constraints(constraints_string):
    if not constraints_string or constraints_string.lower() == 'true':
        track_constraints = True
    elif constraints_string.lower() == 'false':
        track_constraints = False
    else:
        track_constraints = {'mandatory': {}, 'optional': []}
        for constraint_string in constraints_string.split(','):
            constraint = constraint_string.split('=')
            if len(constraint) != 2:
                logging.error('Ignoring malformed constraint: ' + constraint_string)
            continue
        if constraint[0].startswith('goog'):
            track_constraints['optional'].append({constraint[0]: constraint[1]})
        else:
            track_constraints['mandatory'][constraint[0]] = constraint[1]
    return track_constraints

def make_media_stream_constraints(audio, video):
    stream_constraints = (
      {'audio': make_media_track_constraints(audio),
       'video': make_media_track_constraints(video)})
    logging.info('Applying media constraints: ' + str(stream_constraints))
    return stream_constraints

def maybe_add_constraint(constraints, param, constraint):
    if (param.lower() == 'true'):
        constraints['optional'].append({constraint: True})
    elif (param.lower() == 'false'):
        constraints['optional'].append({constraint: False})
    return constraints

def make_pc_constraints(dtls, dscp, ipv6):
    constraints = { 'optional': [] }
    maybe_add_constraint(constraints, dtls, 'DtlsSrtpKeyAgreement')
    maybe_add_constraint(constraints, dscp, 'googDscp')
    maybe_add_constraint(constraints, ipv6, 'googIPv6')
    return constraints

def make_offer_constraints():
    constraints = { 'mandatory': {}, 'optional': [] }
    return constraints

def append_url_arguments(request, link):
    for argument in request.arguments():
        if argument != 'r':
            link += ('&' + cgi.escape(argument, True) + '=' +
                cgi.escape(request.get(argument), True))
    return link