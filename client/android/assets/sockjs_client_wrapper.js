var isMSIE = /*@cc_on!@*/0;
var SockJSClient = new function() {
    this.channels = [];
    
    this.onopen_handlers=[];
    this.onmessage_handlers=[];
    this.onerror_handlers=[];
    this.onclose_handlers=[];
    
    this.reconnect_delays = [1000, 2500, 5000, 10000, 30000, 60000];
    
    this.options = {
        // verbose?
        log: false
    };
    
	this.connect_subscribe_chanel = function(server,user_id,group_id) {
        this.server = server;
        //this.server = "http://192.168.0.102:8080/connect";
        this.apikey = "cnmooc";
        this.channels = [group_id];
        this.reconnect_tries = 0;

		options={log: true, user: user_id};
        // merge options
        for (var attr in options) {
            this.options[attr] = options[attr];
        }

        this.user = this.options.user;
        this.makeConnectionSubscribe();
        var that = this;
    };
    
    this.event_handler_binder=function(event_name,handler){
    	if(event_name === "open"){
    		this.onopen_handlers.push(handler);
    	}else if(event_name === "message"){
    		this.onmessage_handlers.push(handler);
    	}else if(event_name === "close"){
    		this.onclose_handlers.push(handler);
    	}else if(event_name === "error"){
    		this.onerror_handlers.push(handler);
    	}
    };
    
    this.send=function(content){
    	 var that = this;
    	 that.socket.send(content);
    };
    
    this.close=function(){
    	
    };
    
    this.sendtoChanel=function(chanel_id,message){
    	var that = this;
    	that.send("SENDTOCHANEL" + message);
    };

    this.makeConnectionSubscribe = function() {
        var that = this;
        // make a connection
        this.socket = new SockJS(this.server, undefined, 
            {'debug': this.options.log});

        this.socket.onopen = function() {
            that.log("Connection has been estabilished.");
            // reset retries counter
            that.reconnect_tries = 0;
            

            // connect and subscribe to channels
            that.socket.send("CONNECT" + that.user + ":" + that.apikey);

            if (that.channels.length)
                that.socket.send("SUBSCRIBE" + that.channels.join(":"));
            

            for (var i = 0; i < that.onopen_handlers.length; i++) {
                that.onopen_handlers[i]();
            }
        }

        this.socket.onmessage = function(e) {
        	console.log(e);
            //that.log("Message has been received", e.data);
        
            try {
                // try to parse the message as json
                //var json_data = JSON.parse(e.data);
                //e.data = json_data;
                //androidMessageHandler.onMessage(e.data);
            }
            catch(e) {
                // not json, leave it as is
            }

            for (var i = 0; i < that.onmessage_handlers.length; i++) {
            	//console.log(e,e.data);
                that.onmessage_handlers[i](e.data);
            }
        }

        this.socket.onclose = function(e) {
            //that.log("Connection has been lost.");

            if (e.code == 9000 || e.code == 9001 || e.code == 9002) {
                // received "key not good" close message
                that.log("Reconnect supressed because of:", e);
                return;
            }

            var delay = that.reconnect_delays[that.reconnect_tries]
                || that.reconnect_delays[that.reconnect_delays.length - 1];

            that.log("Reconnecting in", delay, "ms...");
            that.reconnect_tries++;
            
            for (var i = 0; i < that.onclose_handlers.length; i++) {
                that.onclose_handlers[i](e.data);
            }
            
            setTimeout(function() {
                that.makeConnectionSubscribe();
            }, delay);
        }
    };

    this.log = function(msg) {
        if (this.options.log
                && "console" in window && "log" in window.console) {

            if (arguments.length == 1) {
                console.log(arguments[0]);
            }
            else {
                if (isMSIE) {
                    var log = Function.prototype.bind.call(console.log, console);
                    log.apply(console, Array.prototype.slice.call(arguments));
                } else {
                    //console.log.apply(console, Array.prototype.slice.call(arguments));
                }
            }
        }
    };
}