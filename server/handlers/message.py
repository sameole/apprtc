
import tornado.web
from channel_mgr.sortingstation import SortingStation

class MessageHandler(tornado.web.RequestHandler):
    def post(self):
        channel = self.get_argument("channel")
        user = self.get_argument("user")
        message = self.request.body
        ss = SortingStation.instance()
        messenger = ss.get_messenger_by_apikey("cnmooc")
        messenger.send_to_chanel_parteners(user,channel,message)
        response = {"state":"SUCCESS"}
        self.set_header("Content-Type", "application/json")
        self.write(response)
        