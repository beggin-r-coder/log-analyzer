package com.anishka.log_analyzer.desktop;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;

/** Dialogs shown before the app window exists (e.g. it could not start), in the app's own dark style. */
final class DesktopDialog {

	private static final Color BG = new Color(0x16, 0x18, 0x1d), LINE = new Color(0x2a, 0x2e, 0x37),
			INK = new Color(0xee, 0xf0, 0xf7), DIM = new Color(0x9a, 0xa3, 0xb8), RED = new Color(0xff, 0x5c, 0x6c);

	private DesktopDialog() {
	}

	static void error(String title, String message, Path logFolder) {
		JDialog d = new JDialog((java.awt.Frame) null, "Log Analyzer", true);
		d.setUndecorated(true);
		JPanel root = new JPanel(new BorderLayout(0, 16));
		root.setBackground(BG);
		root.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE), BorderFactory.createEmptyBorder(24, 26, 20, 26)));

		JPanel head = new JPanel(new BorderLayout(14, 0));
		head.setOpaque(false);
		JLabel icon = new JLabel("!", SwingConstants.CENTER) {
			@Override
			protected void paintComponent(Graphics g) {
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(new Color(0xff, 0x5c, 0x6c, 40));
				g2.fillOval(0, 0, getWidth(), getHeight());
				g2.dispose();
				super.paintComponent(g);
			}
		};
		icon.setForeground(RED);
		icon.setFont(icon.getFont().deriveFont(Font.BOLD, 20f));
		icon.setPreferredSize(new Dimension(40, 40));
		JLabel t = new JLabel(title);
		t.setForeground(INK);
		t.setFont(t.getFont().deriveFont(Font.BOLD, 16f));
		head.add(icon, BorderLayout.WEST);
		head.add(t, BorderLayout.CENTER);

		JTextArea msg = new JTextArea(message);
		msg.setEditable(false);
		msg.setLineWrap(true);
		msg.setWrapStyleWord(true);
		msg.setOpaque(false);
		msg.setForeground(DIM);
		msg.setFont(t.getFont().deriveFont(Font.PLAIN, 13f));
		msg.setBorder(null);
		msg.setColumns(38);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.setOpaque(false);
		if (logFolder != null) {
			buttons.add(button("Open log folder", false, () -> {
				try {
					java.awt.Desktop.getDesktop().open(logFolder.toFile());
				} catch (Exception e) {
					// ignore
				}
			}));
		}
		buttons.add(button("Close", true, d::dispose));

		root.add(head, BorderLayout.NORTH);
		root.add(msg, BorderLayout.CENTER);
		root.add(buttons, BorderLayout.SOUTH);
		d.setContentPane(root);
		d.pack();
		d.setMinimumSize(new Dimension(420, d.getHeight()));
		d.setLocationRelativeTo(null);
		d.setAlwaysOnTop(true);
		d.setVisible(true);
	}

	private static JButton button(String text, boolean primary, Runnable action) {
		JButton b = new JButton(text);
		b.setFocusPainted(false);
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.setFont(b.getFont().deriveFont(Font.BOLD, 12.5f));
		b.setForeground(primary ? BG : INK);
		b.setBackground(primary ? INK : new Color(0x22, 0x25, 0x2c));
		b.setOpaque(true);
		b.setContentAreaFilled(true);
		b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(primary ? INK : LINE), BorderFactory.createEmptyBorder(8, 16, 8, 16)));
		b.addActionListener(e -> action.run());
		b.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseEntered(MouseEvent e) {
				b.setBackground(primary ? Color.WHITE : new Color(0x2a, 0x2e, 0x37));
			}

			@Override
			public void mouseExited(MouseEvent e) {
				b.setBackground(primary ? INK : new Color(0x22, 0x25, 0x2c));
			}
		});
		return b;
	}
}
