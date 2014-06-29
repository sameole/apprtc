import logging
import re

logger = logging.getLogger()

class Messenger(object):
    """
    Handles dispatching messages to Channels and Users
    for given client.
    """
    def __init__(self, apikey, apisecret, *args, **kwargs):
        self.apikey = apikey
        self.apisecret = apisecret
        self.users = {}
        self.channels = {}
        self.user_count = 0

    @staticmethod
    def is_valid_channel_name(name):
        return not re.match("^[a-zA-Z0-9_\-\=\@\,\.\;]{1,64}$", name) is None
    
    def send_to_chanel_parteners(self,user_id,channel,message):
        users = self.get_other_users_in_chanel(user_id,channel)
        return self._send_to_users(users, message)

    def send_to_channel(self, channel, message):
        """
        Sends a message to given channel.
        Returns a count of messages sent.
        """
        users = self.get_users_in_channel(channel)
        return self._send_to_users(users, message)

    def send_to_user(self, userid, message):
        """
        Sends a message to given user.
        Returns a count of messages sent.
        """

        users = self.users.get(userid, [])
        return self._send_to_users(users, message)

    def _send_to_users(self, users, message):
        if users:
            users[0].broadcast(users, message)

        return len(users)

    def register_user(self, user):
        self.users.setdefault(user.userid, []).append(user)

    def subscribe_user_to_channel(self, user, channel):
        if self.is_valid_channel_name(channel):
            self.channels.setdefault(channel, []).append(user)

            logger.debug("%s subscribed to %s." % (user.userid, channel,))
            logger.debug("User count in %s: %d." %
                (channel, self.get_channel_user_count(channel)))
        else:
            logger.debug("Invalid channel name %s." % channel)

    def unregister_user(self, user):
        channels_to_free = []
        #print user.userid + 'unregister' 
        for name in self.channels.iterkeys():
            try:
                self.channels[name].remove(user)

                # as we can't delete keys from the dict as we are iterating
                # over it, we do it outside of this loop
                if len(self.channels[name]) == 0:
                    channels_to_free.append(name)
            except ValueError:
                pass

        # free up the memory used by empty channel index
        for channel in channels_to_free:
            del self.channels[channel]

        self.users[user.userid].remove(user)

        # free up the memory used by empty user index
        if len(self.users[user.userid]) == 0:
            del self.users[user.userid]

    def force_disconnect_user(self, userid):
        handlers = self.users.get(userid, [])

        for handler in handlers:
            handler.force_disconnect()
            
    def is_channel_created(self,chanel):
        for name in self.channels.iterkeys():
            if name == chanel:
                return self.channels[chanel]
            else:
                return None

    def get_user_count(self):
        return len(self.users)

    def get_connections_count(self):
        return sum([len(connections) for connections in self.users.values()])

    def is_user_online(self, userid):
        return bool(self.users.get(userid, 0))

    def get_channel_user_count(self, channel):
        return len(self.get_users_in_channel(channel))

    def get_users_in_channel(self, channel):
        return self.channels.get(channel, [])
    
    def get_other_users_in_chanel(self,user_id,channel):
        all_users = self.channels.get(channel, [])
        other_users = []
        for user in all_users:
            if user.userid != user_id:
                other_users.append(user)
        return other_users
    
    def kickoff_if_logged(self,channel,user_id):
        handlers = self.users.get(user_id, [])
        for handler in handlers:
            self.unregister_user(handler)
            handler.force_disconnect()
            
    
