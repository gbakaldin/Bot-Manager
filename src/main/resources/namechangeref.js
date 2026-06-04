const host = "aypiaigetwey.gwbizquenab.com";
const bot_name_file = 'new_display_name_5k.txt';

let user = 'botaitldl';
let password = "rtav2zxx@3451";
let i_bot = 0;
let bot_count = 1200;

const host_deposit = 'gamems.dev';

let botNames = new Array();

function initName() {
    if (botNames.length == 0) {
        // var fs = require('fs');
        var lineReader = require('readline').createInterface({
            input: require('fs').createReadStream(bot_name_file)
        });
        // fs.appendFile('bot_name.txt', `var bot_names = [`, function(){});
        lineReader.on('line', function (line) {
            // log('Line from file:', line);
            if (line.trim().length > 0) {
                botNames.push(line.trim());
                // fs.appendFile('bot_name.txt', `"${line.trim()}",`, function(){});
            }
        });
        // fs.appendFile('bot_name.txt', `];`, function(){});
    }
}

function change_infor() {
    var i = i_bot;
    if (i_bot >= bot_count) {
        return;
    }
    var https = require('https');
    var ip = (Math.floor(Math.random()*255) + 1) + "." + (Math.floor(Math.random()*255) + 1) + "." + (Math.floor(Math.random()*255) + 1) + "." + (Math.floor(Math.random()*255) + 1);
    var UserInfo = {
        "ip": ip,
        "username": user + (i+1),
        "password": password,
        fg: "f298e7a233c88c9980a7d90dc707fbe7" + Math.floor(Math.random() * 99999),
        app_id: "bc117066",
    };
    var bodyString = JSON.stringify(UserInfo);

    var headers = {
        'Content-Type': 'application/json',
        'Content-Length': bodyString.length
    };

    var options = {
        host: host,
        path: '/user/login.aspx',
        port: 443,
        method: 'POST',
        headers: headers
    };
    var st = new Date().getTime();
    //var loginUrl = "https://api-gateway.go88.pro/user/login.aspx";
    let callback = function (response) {
        let str = '';

        //another chunk of data has been recieved, so append it to `str`
        response.on('data', function (chunk) {
            str += chunk;
        });

        //the whole response has been recieved, so we just print it out here
        response.on('end', function () {
            //log('>>>>>' + str + '<<<<<');
            log('Login time: ' + (new Date().getTime() - st) + 'ms');
            let Jobj = JSON.parse(str);
            if (Jobj.status == 'OK') {
                // change default name
                // if (Jobj.data[0].fullname.indexOf('test_') != -1) {
                _change();
                // } else {
                //   log('Name is ok ' + Jobj.data[0].fullname + ' next...');
                //   i_bot ++;
                //   change_infor();
                // }

                function _change() {
                    // var UserInfo = `{"fullname": "${Jobj.data[0].fullname.replaceAll(' ', '')}", "password": password, "old_password": "123456"}`;
                    var UserInfo = {
                        "fullname": genName(),
                    };
                    let jsBody = JSON.stringify(UserInfo);
                    var headers = {
                        'Content-Type': 'application/json',
                        'Content-Length': jsBody.length,
                        'X-TOKEN': Jobj.data[0].session_id
                    };
                    // log(jsBody);
                    // log(JSON.stringify(headers));
                    var options = {
                        host: host,
                        path: '/user/update.aspx',
                        port: 443,
                        method: 'POST',
                        headers: headers
                    };
                    var ust = new Date().getTime();
                    https.request(options, function (response) {
                        let str = '';
                        response.on('data', function (chunk) {
                            str += chunk;
                        });
                        response.on('end', function () {
                            log(str);
                            log('Update time: ' + (new Date().getTime() - ust) + 'ms');
                            let rs = JSON.parse(str);
                            if (rs.status == 'INVALID') {
                                log('Name is exist, try again..');
                                // retry
                                _change();
                            } else {
                                i_bot ++;
                                change_infor();
                            }
                        });
                    }).write(jsBody);

                }

            }

        });
    };
    // callback is same as in the above seen example.

    https.request(options, callback).write(bodyString);
}

function genName() {
    // log( num.add(1,2) );
    if (botNames.length == 0) {
        return null;
    }
    return botNames[random(0, botNames.length - 1)];
}

function _changeInfo() {
    setTimeout(function(){
        // if (genName() != null) {
        //   log(genName());

        change_infor();
        // } else {
        //   _changeInfo();
        // }
    }, 2000);
}

function log(msg) {
    console.log(new Date() + ' ' + msg);
}

function error(msg) {
    console.error(new Date() + ' ' + msg);
}

function random(min, max) {
    return Math.floor(Math.random() * (max - min + 1) + min);
}

initName();
_changeInfo();

