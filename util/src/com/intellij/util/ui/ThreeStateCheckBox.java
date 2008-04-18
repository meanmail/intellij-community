package com.intellij.util.ui;

import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import javax.swing.plaf.ActionMapUIResource;
import java.awt.*;
import java.awt.event.*;

/**
 * @author spleaner
 */
public class ThreeStateCheckBox extends JCheckBox {
  private ThreeStateModelDecorator myModel;

  public static enum State {
    SELECTED, NOT_SELECTED, DONT_CARE
  }

  public ThreeStateCheckBox() {
    this(null, null, State.DONT_CARE);
  }

  public ThreeStateCheckBox(final State initial) {
    this(null, null, initial);
  }

  public ThreeStateCheckBox(final String text) {
    this(text, null, State.DONT_CARE);
  }

  public ThreeStateCheckBox(final String text, final State initial) {
    this(text, null, initial);
  }

  public ThreeStateCheckBox(final String text, final Icon icon) {
    this(text, icon, State.DONT_CARE);
  }

  public ThreeStateCheckBox(final String text, final Icon icon, final State initial) {
    super(text, icon);

    super.addMouseListener(new MouseAdapter() {
      public void mousePressed(MouseEvent e) {
        grabFocus();
        myModel.nextState();
      }
    });

    ActionMap map = new ActionMapUIResource();
    map.put("pressed", new AbstractAction() {
      public void actionPerformed(ActionEvent e) {
        grabFocus();
        myModel.nextState();
      }
    });

    map.put("released", null);
    SwingUtilities.replaceUIActionMap(this, map);

    myModel = new ThreeStateModelDecorator(getModel());
    setModel(myModel);
    setState(initial);
  }

  @SuppressWarnings({"NonSynchronizedMethodOverridesSynchronizedMethod"})
  public void addMouseListener(MouseListener l) {
  }

  @Override
  public void setSelected(final boolean b) {
    setState(b ? State.SELECTED : State.NOT_SELECTED);
  }

  public void setState(State state) {
    myModel.setState(state);
  }

  public State getState() {
    return myModel.getState();
  }

  @Override
  public void paint(final Graphics g) {
    super.paint(g);
    switch (getState()) {
      case DONT_CARE:
        final Rectangle r = getBounds();
        final Insets i = getInsets();

        Icon icon = getIcon();
        if (icon == null) {
          icon = UIManager.getIcon("CheckBox.icon");
        }

        if (icon != null) {
          final Color selected = UIManager.getColor("CheckBox.focus");
          if (selected != null) {
            g.setColor(selected);
          }

          final int width1 = icon.getIconWidth();
          final int height1 = icon.getIconHeight();
          final int yoffset = height1 / 2 - 1;
          final int xoffset = width1 / 2 - width1 / 5;

          g.fillRect(xoffset + i.left, yoffset + i.top, width1 / 3, 2);
        }
        break;
      default:
        break;
    }
  }

  private static class ThreeStateModelDecorator implements ButtonModel {
    private ButtonModel myOther;

    private ThreeStateModelDecorator(final ButtonModel other) {
      myOther = other;
    }

    private void setState(@NotNull final State state) {
      switch (state) {
        case SELECTED:
          myOther.setArmed(false);
          setPressed(false);
          setSelected(true);
          break;
        case NOT_SELECTED:
          myOther.setArmed(false);
          setPressed(false);
          setSelected(false);
          break;
        case DONT_CARE:
        default:
          myOther.setArmed(true);
          setPressed(false);
          setSelected(false);
          break;
      }
    }

    private State getState() {
      if (isSelected() && !isArmed()) {
        return State.SELECTED;
      }
      else if (isArmed()) {
        return State.DONT_CARE;
      }
      else {
        return State.NOT_SELECTED;
      }
    }

    private void nextState() {
      switch (getState()) {
        case SELECTED:
          setState(State.DONT_CARE);
          break;
        case NOT_SELECTED:
          setState(State.SELECTED);
          break;
        case DONT_CARE:
        default:
          setState(State.NOT_SELECTED);
          break;
      }
    }

    public boolean isArmed() {
      return myOther.isArmed();
    }

    public boolean isSelected() {
      return myOther.isSelected();
    }

    public boolean isEnabled() {
      return myOther.isEnabled();
    }

    public boolean isPressed() {
      return myOther.isPressed();
    }

    public boolean isRollover() {
      return myOther.isRollover();
    }

    public void setArmed(final boolean b) {
    }

    public void setSelected(final boolean b) {
      myOther.setSelected(b);
    }

    public void setEnabled(final boolean b) {
      myOther.setEnabled(b);
    }

    public void setPressed(final boolean b) {
      myOther.setPressed(b);
    }

    public void setRollover(final boolean b) {
      myOther.setRollover(b);
    }

    public void setMnemonic(final int key) {
      myOther.setMnemonic(key);
    }

    public int getMnemonic() {
      return myOther.getMnemonic();
    }

    public void setActionCommand(final String s) {
      myOther.setActionCommand(s);
    }

    public String getActionCommand() {
      return myOther.getActionCommand();
    }

    public void setGroup(final ButtonGroup group) {
      myOther.setGroup(group);
    }

    public void addActionListener(final ActionListener l) {
      myOther.addActionListener(l);
    }

    public void removeActionListener(final ActionListener l) {
      myOther.removeActionListener(l);
    }

    public void addItemListener(final ItemListener l) {
      myOther.addItemListener(l);
    }

    public void removeItemListener(final ItemListener l) {
      myOther.removeItemListener(l);
    }

    public void addChangeListener(final ChangeListener l) {
      myOther.addChangeListener(l);
    }

    public void removeChangeListener(final ChangeListener l) {
      myOther.removeChangeListener(l);
    }

    public Object[] getSelectedObjects() {
      return myOther.getSelectedObjects();
    }
  }
}
