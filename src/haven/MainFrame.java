/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import com.jogamp.common.nio.Buffers;
import com.jogamp.nativewindow.util.PixelFormat;
import com.jogamp.nativewindow.util.PixelRectangle;
import com.jogamp.newt.*;
import com.jogamp.newt.event.*;
import com.jogamp.newt.opengl.GLWindow;
import com.jogamp.opengl.util.AnimatorBase;
import com.jogamp.opengl.util.FPSAnimator;
//import com.jogamp.opengl.util.awt.Screenshot;
import com.jogamp.opengl.*;

import haven.error.GLExceptionHandler;

import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.text.SimpleDateFormat;
import java.util.*;


public class MainFrame implements GLEventListener, Console.Directory {
    private static final String TITLE = "Haven and Hearth (Amber v" + Config.version + ")";

    public static final GLState.Slot<GLState> global = new GLState.Slot<>(GLState.Slot.Type.SYS, GLState.class);
    public static final GLState.Slot<GLState> proj2d = new GLState.Slot<>(GLState.Slot.Type.SYS, GLState.class, global);

    public static UI ui;
    public static int w, h;
    private static int hz = 60;
    private static int bghz = Utils.getprefi("bghz", 60);
    private long drwt, bglt;

    public MouseEvent mousemv;
    public static Queue<InputEvent> events = new LinkedList<>();

    private Resource lastcursor = null;

    private CPUProfile uprof = new CPUProfile(300), rprof = new CPUProfile(300);
    private GPUProfile gprof = new GPUProfile(300);

    private GLState gstate, ostate;
    private GLState.Applier state = null;
    private GLConfig glconf = null;
    public static boolean needtotakescreenshot;
    private static BufferBGL buffer = null;
    private static AnimatorBase animator;
    private GLWindow glw;
    private static GLCapabilities caps;
    public static Coord mousepos = new Coord(0, 0);


    static {
        try {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());

            // Since H&H IPs aren't likely to change (at least mid client run), and the client constantly needs to fetch
            // resources from the server, we enable "cache forever" policy so to overcome sporadic UnknownHostException
            // due to flaky DNS. Bad practice, but still better than forcing the user to modify hosts file.
            // NOTE: this needs to be done early as possible before InetAddressCachePolicy is initialized.
            java.security.Security.setProperty("networkaddress.cache.ttl", "-1");
        } catch (Exception e) {
        }

        System.setProperty("newt.window.icons", "haven/icon.png,haven/icon.png");

        WebBrowser.self = DesktopBrowser.create();
    }

    public MainFrame(GLWindow glw) {
        this.glw = glw;
    }

    public static void main(final String[] args) {
        Config.cmdline(args);

        Coord wndsz = Utils.getprefc("wndsz", new Coord(800, 600));
        w = wndsz.x;
        h = wndsz.y;

        if (Config.playerposfile != null)
            new Thread(new PlayerPosStreamer(), "Player position thread").start();


        // TODO: redo error handler
        final haven.error.ErrorHandler hg = new haven.error.ErrorHandler();
        hg.sethandler(new haven.error.ErrorGui(null) {
            public void errorsent() {
                hg.interrupt();
            }
        });
        ThreadGroup g = hg;

        // TODO setDefaultUncaughtExceptionHandler for render and events thread


        GLProfile prof = GLProfile.getDefault();
        caps = new GLCapabilities(prof);
        caps.setDoubleBuffered(true);
        caps.setAlphaBits(8);
        caps.setRedBits(8);
        caps.setGreenBits(8);
        caps.setBlueBits(8);
        caps.setSampleBuffers(true);
        caps.setNumSamples(4);

        Display display = NewtFactory.createDisplay(null);
        Screen screen = NewtFactory.createScreen(display, 0);
        GLWindow glw = GLWindow.create(screen, caps);
        MainFrame mainframe = new MainFrame(glw);

        setupres();

        dumplist(Resource.remote().loadwaited(), Config.loadwaited);
        dumplist(Resource.remote().cached(), Config.allused);
        if (ResCache.global != null) {
            try {
                Writer w = new OutputStreamWriter(ResCache.global.store("tmp/allused"), "UTF-8");
                try {
                    Resource.dumplist(Resource.remote().used(), w);
                } finally {
                    w.close();
                }
            } catch (IOException e) {
            }
        }

        Runnable rbl = new Runnable() {
            @Override
            public void run() {
                try {
                    try {
                        Session sess = null;
                        while (true) {
                            UI.Runner fun;
                            if (sess == null) {
                                Bootstrap bill = new Bootstrap(Config.defserv, Config.mainport);
                                if ((Config.authuser != null) && (Config.authck != null)) {
                                    bill.setinitcookie(Config.authuser, Config.authck);
                                    Config.authck = null;
                                }
                                fun = bill;
                                glw.setTitle(TITLE);
                            } else {
                                fun = new RemoteUI(sess);
                                glw.setTitle(TITLE + " \u2013 " + sess.username);
                            }

                            ui = mainframe.newui(sess);
                            synchronized (this) {
                                notify();
                            }
                            sess = fun.run(ui);
                        }
                    } catch (InterruptedException e) {
                    }
                } finally {
                    // do something. kill other threads?
                }
            }
        };
        new HackThread(g, rbl, "Haven main thread").start();

        synchronized (rbl) {
            try {
                while (ui == null)
                    rbl.wait();
            } catch (InterruptedException e) {
                // TODO
            }
        }

        animator = new FPSAnimator(glw, 60, true);
        animator.setUpdateFPSFrames(10, null);

        final NEWTMouseAdapter mouseAdapter = new NEWTMouseAdapter(events, mainframe);
        final NEWTKeyAdapter keyAdapter = new NEWTKeyAdapter(events);
        mainframe.glw.addWindowListener(new WindowAdapter() {
            @Override
            public void windowDestroyNotify(WindowEvent e) {
                g.interrupt();
                // Use a dedicate thread to run the stop() to ensure that the animator stops before program exits
                new Thread() {
                    @Override
                    public void run() {
                        animator.stop();
                        System.exit(0);
                    }
                }.start();
            }

            @Override
            public void windowGainedFocus(WindowEvent e) {
                animator.setUpdateFPSFrames(hz, null);
            }

            @Override
            public void windowLostFocus(WindowEvent e) {
                hz = (int)animator.getTotalFPS();
                animator.setUpdateFPSFrames(bghz, null);
            }
        });

        glw.addMouseListener(mouseAdapter);
        glw.addKeyListener(keyAdapter);
        glw.addGLEventListener(mainframe);
        glw.setSize(w, h);
        glw.setTitle(TITLE);
        animator.start();
        animator.setUncaughtExceptionHandler(new GLExceptionHandler());
        glw.setVisible(true);
    }

    public static void setupres() {
        if (ResCache.global != null)
            Resource.setcache(ResCache.global);
        if (Config.resurl != null)
            Resource.addurl(Config.resurl);
        if (ResCache.global != null) {
            try {
                Resource.loadlist(Resource.remote(), ResCache.global.fetch("tmp/allused"), -10);
            } catch (IOException e) {
            }
        }
        if (!Config.nopreload) {
            try {
                InputStream pls;
                pls = Resource.class.getResourceAsStream("res-preload");
                if (pls != null)
                    Resource.loadlist(Resource.remote(), pls, -5);
                pls = Resource.class.getResourceAsStream("res-bgload");
                if (pls != null)
                    Resource.loadlist(Resource.remote(), pls, -10);
            } catch (IOException e) {
                throw (new Error(e));
            }
        }
    }

    private UI newui(Session sess) {
        if (ui != null)
            ui.destroy();
        ui = new UI(new Coord(w, h), sess);
        ui.root.guprof = uprof;
        ui.root.grprof = rprof;
        ui.root.ggprof = gprof;
        ui.cons.add(this);
        if (glconf != null)
            ui.cons.add(glconf);
        return ui;
    }

    private static void dumplist(Collection<Resource> list, String fn) {
        try {
            if (fn != null) {
                Writer w = new OutputStreamWriter(new FileOutputStream(fn), "UTF-8");
                try {
                    Resource.dumplist(list, w);
                } finally {
                    w.close();
                }
            }
        } catch (IOException e) {
            throw (new RuntimeException(e));
        }
    }

    @Override
    public void init(GLAutoDrawable glAutoDrawable) {
        GL gl = glAutoDrawable.getGL();
        gl.setSwapInterval(0);

        glconf = GLConfig.fromgl(gl, glAutoDrawable.getContext(), caps);
        glconf.pref = GLSettings.load(glconf, true);
        ui.cons.add(glconf);
        final haven.error.ErrorHandler h = haven.error.ErrorHandler.find();
        if (h != null) {
            h.lsetprop("gl.vendor", gl.glGetString(gl.GL_VENDOR));
            h.lsetprop("gl.version", gl.glGetString(gl.GL_VERSION));
            h.lsetprop("gl.renderer", gl.glGetString(gl.GL_RENDERER));
            h.lsetprop("gl.exts", Arrays.asList(gl.glGetString(gl.GL_EXTENSIONS).split(" ")));
            h.lsetprop("gl.caps", glAutoDrawable.getChosenGLCapabilities().toString());
            h.lsetprop("gl.conf", glconf);
        }
        gstate = new GLState() {
            @Override
            public void apply(GOut g) {
                BGL gl = g.gl;
                gl.glColor3f(1, 1, 1);
                gl.glPointSize(4);
                gl.joglSetSwapInterval(1);
                gl.glEnable(GL.GL_BLEND);
                //gl.glEnable(GL.GL_LINE_SMOOTH);
                gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
                if (g.gc.glmajver >= 2)
                    gl.glBlendEquationSeparate(GL.GL_FUNC_ADD, GL2.GL_MAX);
                if (g.gc.havefsaa()) {
                    /* Apparently, having sample
                     * buffers in the config enables
                     * multisampling by default on
                     * some systems. */
                    g.gl.glDisable(GL.GL_MULTISAMPLE);
                }
                GOut.checkerr(gl);
            }

            @Override
            public void unapply(GOut g) {
            }

            @Override
            public void prep(Buffer buf) {
                buf.put(global, this);
            }
        };
    }

    @Override
    public void dispose(GLAutoDrawable glAutoDrawable) {
    }

    @Override
    public void display(GLAutoDrawable glAutoDrawable) {
        if (ui == null)
            return;

        GL2 gl = glAutoDrawable.getGL().getGL2();

        if ((state == null) || (state.cgl.gl != gl))
            state = new GLState.Applier(new CurrentGL(gl, glconf));

        UI ui = this.ui;

        synchronized (ui) {
            if (ui.sess != null)
                ui.sess.glob.ctick();
            dispatchInputEvents();
            ui.tick();
            if (ui.root.sz.x != w || ui.root.sz.y != h)
                ui.root.resize(new Coord(w, h));
        }

        buffer = new BufferBGL();

        long now = System.currentTimeMillis();
        rootdraw(state, ui, buffer);
        drwt = (System.currentTimeMillis() - now);

        now = System.currentTimeMillis();
        buffer.run(gl);
        bglt = (System.currentTimeMillis() - now);

        ui.audio.cycle();

        if (needtotakescreenshot) {
           // takescreenshot(glAutoDrawable.getWidth(), glAutoDrawable.getHeight());
            needtotakescreenshot = false;
        }
    }

    private void takescreenshot(int width, int height) {
        try {
            String curtimestamp = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss.SSS").format(new Date());
            File outputfile = new File(String.format("screenshots/%s.jpg", curtimestamp));
            outputfile.getParentFile().mkdirs();
           // Screenshot.writeToFile(outputfile, width, height);
            ui.root.findchild(GameUI.class).msg(String.format("Screenshot has been saved as \"%s\"", outputfile.getName()), Color.WHITE);
        } catch (Exception ex) {
            System.out.println("Unable to take screenshot: " + ex.getMessage());
        }
    }

    private void dispatchInputEvents() {
        synchronized (events) {
            if (mousemv != null) {
                mousepos = new Coord(mousemv.getX(), mousemv.getY());
                ui.mousemove(mousemv, mousepos);
                mousemv = null;
            }
            InputEvent e;
            while ((e = events.poll()) != null) {
                if (e instanceof MouseEvent) {
                    MouseEvent me = (MouseEvent) e;
                    if (me.getEventType() == MouseEvent.EVENT_MOUSE_PRESSED) {
                        ui.mousedown(me, new Coord(me.getX(), me.getY()), me.getButton());
                    } else if (me.getEventType() == MouseEvent.EVENT_MOUSE_RELEASED) {
                        ui.mouseup(me, new Coord(me.getX(), me.getY()), me.getButton());
                    } else if (me.getEventType() == MouseEvent.EVENT_MOUSE_WHEEL_MOVED) {
                        float[] rot = me.getRotation();
                        int rotval = me.isShiftDown() ? (int)rot[0] : (int)rot[1];
                        ui.mousewheel(me, new Coord(me.getX(), me.getY()), -1*rotval);
                    }
                } else if (e instanceof KeyEvent) {
                    KeyEvent ke = (KeyEvent) e;
                    if (ke.getEventType() == KeyEvent.EVENT_KEY_PRESSED) {
                        ui.keydown(ke);
                    } else if (ke.getEventType() == KeyEvent.EVENT_KEY_RELEASED) {

                        // FIXME:
                        // AWT produces typed event with keyCode set to 0
                        // while NEWT's released event has a proper keyCode set (for printable keys)
                        // and since internal haven code assumes keyCode to be 0 for typed event - we modify it

                        // also interestingly enough, simulating AWT semantics with (isPrintableKey() && !isAutoRepeat())
                        // doesn't work:
                        // AWT - produces typed event
                        // NEWT - isPrintableKey returns false

                        short keyCode = modifyKeyCode(ke, (short) 0);
                        ui.type(ke);
                        modifyKeyCode(ke, keyCode);

                        ui.keyup(ke);
                    }
                }
                ui.lastevent = System.currentTimeMillis();
            }
        }

    }
    
    private short modifyKeyCode(KeyEvent ke, short newKeyCode) {
        short keyCode = 0;

        try {
            Field f = KeyEvent.class.getDeclaredField("keyCode");

            // make final field accessible
            f.setAccessible(true);
            int modifiers = f.getModifiers();
            Field modifierField = f.getClass().getDeclaredField("modifiers");
            modifiers = modifiers & ~Modifier.FINAL;
            modifierField.setAccessible(true);
            modifierField.setInt(f, modifiers);

            keyCode = (short) f.get(ke);
            f.set(ke, newKeyCode);
        } catch (NoSuchFieldException e1) {
            e1.printStackTrace();
        } catch (IllegalAccessException e1) {
            e1.printStackTrace();
        }

        return keyCode;
    }

    private void rootdraw(GLState.Applier state, UI ui, BGL gl) {
        GLState.Buffer ibuf = new GLState.Buffer(state.cfg);
        gstate.prep(ibuf);
        ostate.prep(ibuf);
        GOut g = new GOut(gl, state.cgl, state.cfg, state, ibuf, new Coord(w, h));
        state.set(ibuf);

        g.state(ostate);
        g.apply();
        gl.glClearColor(0, 0, 0, 1);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT);

        synchronized (ui) {
            ui.draw(g);
        }

        if (Config.showfps) {
            FastText.aprint(g, new Coord(w - 50, 15), 0, 1, "FPS: " + (int) animator.getLastFPS());
        }

        if (Config.dbtext) {
            int y = h - 165;
            FastText.aprintf(g, new Coord(10, y -= 15), 0, 1, "FPS: %d (drw: %d, bgl: %d)", (int) animator.getLastFPS(), drwt, bglt);
            Runtime rt = Runtime.getRuntime();
            long free = rt.freeMemory(), total = rt.totalMemory();
            FastText.aprintf(g, new Coord(10, y -= 15), 0, 1, "Mem: %,011d/%,011d/%,011d/%,011d", free, total - free, total, rt.maxMemory());
            FastText.aprintf(g, new Coord(10, y -= 15), 0, 1, "Tex-current: %d", TexGL.num());
            FastText.aprintf(g, new Coord(10, y -= 15), 0, 1, "GL progs: %d", g.st.numprogs());
            GameUI gi = ui.root.findchild(GameUI.class);
            if ((gi != null) && (gi.map != null)) {
                try {
                    FastText.aprintf(g, new Coord(10, y -= 15), 0, 1, "Mapview: %s", gi.map);
                } catch (Loading e) {
                }
                if (gi.map.rls != null)
                    FastText.aprintf(g, new Coord(10, y -= 15), 0, 1, "Rendered: %,d+%,d(%,d), cached %,d/%,d+%,d(%,d)", gi.map.rls.drawn, gi.map.rls.instanced, gi.map.rls.instancified, gi.map.rls.cacheroots, gi.map.rls.cached, gi.map.rls.cacheinst, gi.map.rls.cacheinstn);
            }
            if (Resource.remote().qdepth() > 0)
                FastText.aprintf(g, new Coord(10, y -= 15), 0, 1, "RQ depth: %d (%d)", Resource.remote().qdepth(), Resource.remote().numloaded());
        }
        Object tooltip;
        try {
            synchronized (ui) {
                tooltip = ui.root.tooltip(mousepos, ui.root);
            }
        } catch (Loading e) {
            tooltip = "...";
        }
        Tex tt = null;
        if (tooltip != null) {
            if (tooltip instanceof Text) {
                tt = ((Text) tooltip).tex();
            } else if (tooltip instanceof Tex) {
                tt = (Tex) tooltip;
            } else if (tooltip instanceof Indir<?>) {
                Indir<?> t = (Indir<?>) tooltip;
                Object o = t.get();
                if (o instanceof Tex)
                    tt = (Tex) o;
            } else if (tooltip instanceof String) {
                if (((String) tooltip).length() > 0)
                    tt = (Text.render((String) tooltip)).tex();
            }
        }
        if (tt != null) {
            Coord sz = tt.sz();
            Coord pos = mousepos.add(sz.inv());
            if (pos.x < 0)
                pos.x = 0;
            if (pos.y < 0)
                pos.y = 0;
            g.chcolor(244, 247, 21, 192);
            g.rect(pos.add(-3, -3), sz.add(6, 6));
            g.chcolor(35, 35, 35, 192);
            g.frect(pos.add(-2, -2), sz.add(4, 4));
            g.chcolor();
            g.image(tt, pos);
        }
        ui.lasttip = tooltip;
        Resource curs = ui.root.getcurs(mousepos);
        if (curs != null && curs != lastcursor) {
            BufferedImage img = curs.layer(Resource.imgc).img;

            int[] pixels = new int[img.getWidth() * img.getHeight()];
            img.getRGB(0, 0, img.getWidth(), img.getHeight(), pixels, 0, img.getWidth());
            final IntBuffer pixelIntBuff = Buffers.newDirectIntBuffer(pixels);
            final ByteBuffer pixelBuff = Buffers.copyIntBufferAsByteBuffer(pixelIntBuff);

            PixelRectangle.GenericPixelRect pixelRect = new PixelRectangle.GenericPixelRect(
                    PixelFormat.BGRA8888,
                    new com.jogamp.nativewindow.util.Dimension(img.getWidth(), img.getHeight()),
                    0,
                    false,
                    pixelBuff);

            Display.PointerIcon picon = glw.getScreen().getDisplay().createPointerIcon(pixelRect, 0, 0);
            glw.setPointerIcon(picon);
            lastcursor = curs;
        }
        state.clean();
        GLObject.disposeall(state.cgl, gl);
    }

    public static abstract class OrthoState extends GLState {
        protected abstract Coord sz();

        public void apply(GOut g) {
            Coord sz = sz();
            g.st.proj = Projection.makeortho(new Matrix4f(), 0, sz.x, sz.y, 0, -1, 1);
        }

        public void unapply(GOut g) {
        }

        public void prep(Buffer buf) {
            buf.put(proj2d, this);
        }

        public static OrthoState fixed(final Coord sz) {
            return (new OrthoState() {
                protected Coord sz() {
                    return (sz);
                }
            });
        }
    }

    @Override
    public void reshape(GLAutoDrawable glAutoDrawable, int x, int y, int w, int h) {
        this.w = w;
        this.h = h;
        Utils.setprefc("wndsz", new Coord(w, h));
        ostate = OrthoState.fixed(new Coord(w, h));
    }

    private Map<String, Console.Command> cmdmap = new TreeMap<String, Console.Command>();

    {
        cmdmap.put("hz", new Console.Command() {
            public void run(Console cons, String[] args) {
                animator.setUpdateFPSFrames(Integer.parseInt(args[1]), null);
            }
        });
        cmdmap.put("bghz", new Console.Command() {
            public void run(Console cons, String[] args) {
                bghz = Integer.parseInt(args[1]);
                Utils.setprefi("bghz", bghz);
            }
        });
    }

    public Map<String, Console.Command> findcmds() {
        return cmdmap;
    }
}
