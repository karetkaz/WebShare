package kmz.webshare;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;

import javax.swing.*;
import javax.swing.text.BadLocationException;

public class WebShareUi extends JFrame {
    /**
     * The text area which is used for displaying logging information.
     */

    private static class CustomOutputStream extends OutputStream {
        private JTextArea textArea;

        public CustomOutputStream(JTextArea textArea) {
            this.textArea = textArea;
        }

        @Override
        public void write(int b) throws IOException {
            // redirects data to the text area
            textArea.append(String.valueOf((char)b));
            // scrolls the text area to the end of data
            textArea.setCaretPosition(textArea.getDocument().getLength());
        }
    }

    public WebShareUi() throws IOException {
        super("WebShare");

        final JTextArea textArea = new JTextArea(50, 10);
        textArea.setEditable(false);
        PrintStream printStream = new PrintStream(new CustomOutputStream(textArea));

        // keeps reference of standard output stream
        final PrintStream standardOut = System.out;

        // re-assigns standard output stream and error output stream
        System.setOut(printStream);
        System.setErr(printStream);

        final JButton btnStart = new JButton("Start");
        final JButton btnClear = new JButton("Clear");
        final JButton btnBrowse = new JButton("Browse");
        final JCheckBox chkWritable = new JCheckBox("RW");
        final JTextField workDir = new JTextField(new java.io.File(".").getCanonicalPath());

        // creates the GUI
        setLayout(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.insets = new Insets(10, 10, 10, 10);
        constraints.anchor = GridBagConstraints.WEST;

        add(btnStart, constraints);

        constraints.gridx = 1;
        btnClear.setVisible(false);
        add(btnClear, constraints);
        constraints.gridx = 2;
        add(btnBrowse, constraints);
        constraints.gridx = 3;
        add(chkWritable, constraints);
        constraints.gridx = 4;
        constraints.anchor = GridBagConstraints.EAST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        add(workDir, constraints);

        constraints.gridx = 0;
        constraints.gridy = 1;
        constraints.gridwidth = 5;
        constraints.fill = GridBagConstraints.BOTH;
        constraints.weightx = 1.0;
        constraints.weighty = 1.0;

        add(new JScrollPane(textArea), constraints);

        btnBrowse.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                JFileChooser chooser = new JFileChooser();
                chooser.setCurrentDirectory(new java.io.File(workDir.getText()));
                chooser.setDialogTitle("Select directory to share");
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                chooser.setAcceptAllFileFilterUsed(false);

                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    try {
                        workDir.setText(chooser.getSelectedFile().getCanonicalPath());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });

        btnStart.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent evt) {
                ArrayList<String> args = new ArrayList<String>();
                //args.add("-repo"); args.add("http://www.url.dom");
                //args.add("-host"); args.add("localhost");
                //args.add("-port"); args.add("8090");
                //args.add("-auth"); args.add("username:password");
                //args.add("-log"); args.add("logFileName");
                //args.add("-n"); args.add("2048");

                if (chkWritable.isSelected()) {
                    args.add("-write");
                }
                args.add(workDir.getText());    // this must be the last argument

                workDir.setEditable(false);
                chkWritable.setEnabled(false);
                btnStart.setVisible(false);
                btnBrowse.setVisible(false);
                btnClear.setVisible(true);

                try {
                    WebShare.main(args.toArray(new String[args.size()]));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        // adds event handler for button Clear
        btnClear.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent evt) {
                // clears the text area
                try {
                    textArea.getDocument().remove(0, textArea.getDocument().getLength());
                } catch (BadLocationException ex) {
                    ex.printStackTrace();
                }
            }


        });

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(480, 320);
        setLocationRelativeTo(null); // centers on screen
    }
}
