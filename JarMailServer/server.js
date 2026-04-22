const express = require('express');
const bodyParser = require('body-parser');
const fs = require('fs');
const path = require('path');
const nodemailer = require('nodemailer');
const { ImapFlow } = require('imapflow');
const simpleParser = require('mailparser').simpleParser;

const app = express();
const port = 3000;

const GMAIL_USER = 'yourmailadress@gmail.com'; 
const GMAIL_PASS = 'secretgmailpass'; 
const SECRET_TOKEN = 'secrettoken'; 

const STORAGE_PATH = './mail_vault'; 
const ATTACH_PATH = path.join(STORAGE_PATH, 'attachments');
const PUBLIC_PATH = './public'; 

if (!fs.existsSync(STORAGE_PATH)) fs.mkdirSync(STORAGE_PATH);
if (!fs.existsSync(ATTACH_PATH)) fs.mkdirSync(ATTACH_PATH);
if (!fs.existsSync(PUBLIC_PATH)) fs.mkdirSync(PUBLIC_PATH);

const cleanText = (str) => {
    if (!str) return "";
    return str
        .replace(/[\uD800-\uDBFF][\uDC00-\uDFFF]/g, '') 
        .replace(/[^\u0000-\uFFFF]/g, '');            
};

const cleanMetadata = (str) => {
    return cleanText(str)
        .replace(/["\\\n\r\t]/g, ' ')
        .trim();
};

const cleanBody = (str) => {
    return cleanText(str);
};

const getMails = (file) => {
    const filePath = path.join(STORAGE_PATH, file);
    return fs.existsSync(filePath) ? JSON.parse(fs.readFileSync(filePath, 'utf8')) : [];
};

const saveMails = (file, mails) => {
    fs.writeFileSync(path.join(STORAGE_PATH, file), JSON.stringify(mails, null, 2), 'utf8');
};


const syncGmail = async () => {
    const client = new ImapFlow({
        host: 'imap.gmail.com', port: 993, secure: true,
        auth: { user: GMAIL_USER, pass: GMAIL_PASS }, logger: false 
    });
    console.log("[IMAP] Syncing with Gmail...");
    try {
        await client.connect();
        await syncFolder(client, 'INBOX', 'inbox.json');
        try { await syncFolder(client, '[Gmail]/Sent Mail', 'sent.json'); } catch(e) {}
        await client.logout();
        console.log("[IMAP] Sync Complete.");
    } catch (err) { console.error("[IMAP Error]", err.message); }
};

const syncFolder = async (client, folderName, jsonFile) => {
    let lock = await client.getMailboxLock(folderName);
    try {
        await client.mailboxOpen(folderName);
        let mails = getMails(jsonFile);
        let lastUid = mails.reduce((max, m) => Math.max(max, m.uid || 0), 0);
        let fetchQuery = { uid: `${lastUid + 1}:*` };
        
        for await (let msg of client.fetch(fetchQuery, { uid: true, source: true })) {
            const parsed = await simpleParser(msg.source);
            let attaches = [];
            if (parsed.attachments) {
                parsed.attachments.forEach(att => {
                    let safeName = Date.now() + "_" + att.filename;
                    fs.writeFileSync(path.join(ATTACH_PATH, safeName), att.content);
                    attaches.push({ 
                        name: cleanMetadata(att.filename), 
                        url: `/download/${safeName}`, 
                        size: (att.size / 1024).toFixed(1) + " KB" 
                    });
                });
            }
            let newMail = {
                id: msg.uid.toString(), uid: msg.uid,
                sender: cleanMetadata(parsed.from ? parsed.from.text : 'Unknown'),
                subject: cleanMetadata(parsed.subject || '(No Subject)'),
                body: cleanBody(parsed.text || ''), 
                date: parsed.date ? parsed.date.toLocaleString() : '',
                read: false, attachments: attaches
            };
            if (!mails.find(m => m.uid === newMail.uid)) {
                mails.unshift(newMail);
                console.log(`[SYNCED] ${newMail.subject}`);
            }
        }
        saveMails(jsonFile, mails);
    } finally { lock.release(); }
};
setInterval(syncGmail, 120000);


app.use(express.static(PUBLIC_PATH, {
    setHeaders: (res, p) => {
        if (p.endsWith('.jad')) res.setHeader('Content-Type', 'text/vnd.sun.j2me.app-descriptor');
        if (p.endsWith('.jar')) res.setHeader('Content-Type', 'application/java-archive');
    }
}));
app.use(bodyParser.urlencoded({ extended: true }));

app.use((req, res, next) => {
    if (req.url.indexOf(';') > -1) req.url = req.url.substring(0, req.url.indexOf(';'));
    if (req.path.endsWith('.jad') || req.path.endsWith('.jar')) return next();
    if (req.headers['x-jarmail-token'] !== SECRET_TOKEN) return res.status(403).send("Forbidden");
    next();
});

app.get(['/inbox', '/sentbox'], (req, res) => {
    const boxFile = req.path === '/inbox' ? 'inbox.json' : 'sent.json';
    const mails = getMails(boxFile).slice(0, 25).map(({body, attachments, ...meta}) => ({
        ...meta, hasAttachments: (attachments && attachments.length > 0)
    }));
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.send(JSON.stringify(mails));
});

app.get('/body_raw', (req, res) => {
    const box = req.query.box === 'inbox' ? 'inbox.json' : 'sent.json';
    const mail = getMails(box).find(m => m.id === req.query.id);
    res.setHeader('Content-Type', 'text/plain; charset=utf-8');
    res.send(mail ? cleanBody(mail.body) : "Message not found.");
});

app.get('/detail', (req, res) => {
    const box = req.query.box === 'inbox' ? 'inbox.json' : 'sent.json';
    const mail = getMails(box).find(m => m.id === req.query.id);
    if (!mail) return res.status(404).json({error: "Not found"});
    const { body, ...safeData } = mail; 
    res.json(safeData);
});

app.post('/send', async (req, res) => {
    const transporter = nodemailer.createTransport({ service: 'gmail', auth: { user: GMAIL_USER, pass: GMAIL_PASS } });
    try {
        await transporter.sendMail({ from: GMAIL_USER, to: req.body.to, subject: req.body.subject, text: req.body.body });
        res.send("OK");
    } catch (e) { res.status(500).send("Error"); }
});

app.get('/download/:name', (req, res) => {
    const f = path.join(ATTACH_PATH, req.params.name);
    if (fs.existsSync(f)) res.download(f); else res.status(404).send("Missing");
});

app.listen(port, '0.0.0.0', () => {
    console.log(`J2ME-JarMail Active at: http://x.x.x.x:${port}`);
    syncGmail();
});