from handlers.index import IndexHandler
from handlers.message import MessageHandler

url_handler_dict=[
  ('/', IndexHandler),
  ('/message', MessageHandler)
]
