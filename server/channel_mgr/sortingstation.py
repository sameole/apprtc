import logging
from messenger import Messenger
import redis

logger = logging.getLogger()

class SortingStation(object):
    """ Handles dispatching messages to Messengers. """
    _instance = None
    def __init__(self, *args, **kwargs):
        if self._instance:
            raise Exception("SortingStation already initialized.")

        self.messengers_by_apikey = {}
        #self.redis_client = redis.Redis("112.126.69.8",6379)
        self.redis_client = redis.Redis()

        SortingStation._instance = self

    @staticmethod
    def instance():
        return SortingStation._instance

    def create_messenger(self, apikey, apisecret):
        messenger = Messenger(apikey, apisecret)

        self.messengers_by_apikey[apikey] = messenger

    def delete_messenger(self, messenger):
        del self.messengers_by_apikey[messenger.apikey]

    def get_messenger_by_apikey(self, apikey):
        return self.messengers_by_apikey.get(apikey, None)
    
    def publish_message(self,channel,message):
        print 'pusblish message',channel
        self.redis_client.publish(channel,message)
