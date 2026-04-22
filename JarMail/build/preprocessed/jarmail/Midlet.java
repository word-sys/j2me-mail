package jarmail;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import java.io.*;
import java.util.*;

public class Midlet extends MIDlet implements CommandListener, Runnable, ItemCommandListener {
    private Display display;
    private List inboxList;
    private Form detailsForm, loadingForm, composeForm;
    private TextField toField, subjectField, bodyField;
    private Command exitCmd, backCmd, refreshCmd, composeCmd, sendCmd, deleteCmd, replyCmd, sentboxCmd, inboxCmd, searchCmd;

    private String serverUrl = "https://serverurl"; 
    private String apiToken = "secretkey"; 
    
    private String currentBox = "/inbox";
    private String searchQuery = "";
    private int task = 0; 
    private Vector mailIds = new Vector();
    private String selectedId = "";
    private String fetchedBody = "";
    private Hashtable activeMail;

    public void startApp() {
        if (display == null) {
            display = Display.getDisplay(this);
            exitCmd = new Command("Exit", Command.EXIT, 1);
            backCmd = new Command("Back", Command.BACK, 2);
            refreshCmd = new Command("Refresh", Command.SCREEN, 3);
            composeCmd = new Command("Compose", Command.SCREEN, 4);
            sentboxCmd = new Command("Sentbox", Command.SCREEN, 5);
            inboxCmd = new Command("Inbox", Command.SCREEN, 6);
            deleteCmd = new Command("Delete", Command.HELP, 7);
            searchCmd = new Command("Search", Command.SCREEN, 8);
            replyCmd = new Command("Reply", Command.OK, 1);
            sendCmd = new Command("Send", Command.OK, 1);

            inboxList = new List("JarMail", List.IMPLICIT);
            inboxList.addCommand(exitCmd);
            inboxList.addCommand(refreshCmd);
            inboxList.addCommand(composeCmd);
            inboxList.addCommand(sentboxCmd);
            inboxList.addCommand(inboxCmd);
            inboxList.addCommand(deleteCmd);
            inboxList.addCommand(searchCmd);
            inboxList.setCommandListener(this);

            detailsForm = new Form("View Mail");
            detailsForm.addCommand(backCmd);
            detailsForm.addCommand(replyCmd);
            detailsForm.setCommandListener(this);

            composeForm = new Form("Compose");
            toField = new TextField("To:", "", 50, TextField.EMAILADDR);
            subjectField = new TextField("Sub:", "", 50, TextField.ANY);
            bodyField = new TextField("Msg:", "", 2000, TextField.ANY);
            composeForm.append(toField);
            composeForm.append(subjectField);
            composeForm.append(bodyField);
            composeForm.addCommand(backCmd);
            composeForm.addCommand(sendCmd);
            composeForm.setCommandListener(this);

            loadingForm = new Form("Connecting");
            loadingForm.append(new Gauge("Syncing...", false, 10, 5));
            doTask(0);
        }
    }

    private void doTask(int t) {
        this.task = t;
        display.setCurrent(loadingForm);
        new Thread(this).start();
    }

    public void run() {
        try {
            if (task == 0) {
                final String res = fetchHttp(serverUrl + currentBox + "?q=" + searchQuery, null);
                display.callSerially(new Runnable() { public void run() { updateUI(res); } });
            } else if (task == 4) {
                String b = currentBox.equals("/inbox") ? "inbox" : "sent";
                String metaJson = fetchHttp(serverUrl + "/detail?id=" + selectedId + "&box=" + b, null);
                activeMail = (Hashtable) MiniJSON.parse(metaJson);
                fetchedBody = fetchHttp(serverUrl + "/body_raw?id=" + selectedId + "&box=" + b, null);
                
                display.callSerially(new Runnable() { public void run() { showDetailUI(); } });
            } else if (task == 1) {
                fetchHttp(serverUrl + "/send", "to="+toField.getString()+"&subject="+subjectField.getString()+"&body="+bodyField.getString());
                doTask(0);
            }
        } catch (final Exception e) {
            display.callSerially(new Runnable() { public void run() { 
                Alert a = new Alert("Error", e.toString(), null, AlertType.ERROR);
                a.setTimeout(Alert.FOREVER);
                display.setCurrent(a, inboxList);
            } });
        }
    }

    private String fetchHttp(String url, String post) throws IOException {
        HttpConnection h = null;
        InputStream i = null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            h = (HttpConnection) Connector.open(NetworkHelper.makeBBUrl(url));
            h.setRequestProperty("X-JarMail-Token", apiToken);
            h.setRequestMethod(post != null ? "POST" : "GET");

            if (post != null) {
                h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                OutputStream o = h.openOutputStream();
                o.write(post.getBytes());
                o.close();
            }

            if (h.getResponseCode() == HttpConnection.HTTP_OK) {
                i = h.openInputStream();
                byte[] buffer = new byte[1024];
                int len;
                while ((len = i.read(buffer)) != -1) {
                    bos.write(buffer, 0, len);
                }
                return new String(bos.toByteArray(), "UTF-8");
            } else {
                throw new IOException("HTTP " + h.getResponseCode());
            }
        } finally {
            try { bos.close(); } catch(Exception e) {}
            if (i != null) i.close();
            if (h != null) h.close();
        }
    }

    private void updateUI(String json) {
        inboxList.deleteAll(); mailIds.removeAllElements();
        try {
            Vector mails = (Vector) MiniJSON.parse(json);
            if (mails.size() == 0) { inboxList.append("(Empty)", null); }
            for (int i = 0; i < mails.size(); i++) {
                Hashtable m = (Hashtable) mails.elementAt(i);
                mailIds.addElement(m.get("id"));
                String u = (currentBox.equals("/inbox") && m.get("read").toString().equals("false")) ? "(!) " : "  ";
                inboxList.append(u + m.get("sender") + "\n" + m.get("subject"), null);
            }
        } catch (Exception e) {
            inboxList.append("LIST ERROR", null);
            inboxList.append(json.length() > 60 ? json.substring(0, 60) : json, null);
        }
        display.setCurrent(inboxList);
    }

    private void showDetailUI() {
        detailsForm.deleteAll();
        detailsForm.append(new StringItem("From: " + activeMail.get("sender"), fetchedBody));
        
        Vector attaches = (Vector) activeMail.get("attachments");
        if (attaches != null) {
            for (int i = 0; i < attaches.size(); i++) {
                Hashtable at = (Hashtable) attaches.elementAt(i);
                StringItem link = new StringItem(null, "["+at.get("name")+"]", Item.BUTTON);
                link.setDefaultCommand(new Command("Open", Command.ITEM, 1));
                link.setItemCommandListener(this);
                detailsForm.append(link);
            }
        }
        display.setCurrent(detailsForm);
    }

    public void commandAction(Command c, Item item) {
        if (c.getLabel().equals("Open")) {
        }
    }

    public void commandAction(Command c, Displayable d) {
        if (c == exitCmd) notifyDestroyed();
        else if (c == backCmd) display.setCurrent(inboxList);
        else if (c == refreshCmd) doTask(0);
        else if (c == sentboxCmd) { currentBox = "/sentbox"; doTask(0); }
        else if (c == inboxCmd) { currentBox = "/inbox"; doTask(0); }
        else if (c == sendCmd) doTask(1);
        else if (c == List.SELECT_COMMAND) {
            selectedId = (String) mailIds.elementAt(inboxList.getSelectedIndex());
            doTask(4);
        }
    }

    public void pauseApp() {}
    public void destroyApp(boolean b) {}
}