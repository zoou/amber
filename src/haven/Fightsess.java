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

import java.text.DecimalFormat;
import java.util.*;
import java.awt.Color;
import java.awt.event.KeyEvent;
import java.awt.event.InputEvent;

public class Fightsess extends Widget {
    public static final Tex meters = Resource.loadtex("gfx/hud/combat/cmbmeters");
    private static final Text.Foundry cdfndr = new Text.Foundry(Text.serif, 18).aa(true);
    private static final Color cdclrpos = new Color(128, 128, 255);
    private static final Color cdclrneg = new Color(239, 41, 41);
    public static final Tex lframe = Resource.loadtex("gfx/hud/combat/lframe");
    public static final int actpitch = 50;
    public final Indir<Resource>[] actions;
    public final boolean[] dyn;
    public int use = -1, useb = -1;
    public Coord pcc;
    public int pho;
    private final Fightview fv;
    private static final DecimalFormat cdfmt = new DecimalFormat("#.#");
    private static final Map<Long, Tex> cdvalues = new HashMap<Long, Tex>(7);

    private static final Map<String, Integer> atkcds = new HashMap<String, Integer>(9){{
        put("Chop", 50);
        put("Cleave", 80);
        put("Haymaker", 60);
        put("Kick", 45);
        put("Knock Its Teeth Out", 35);
        put("Left Hook", 40);
        put("Low Blow", 30);
        put("Punch", 30);
        put("Sting", 35);
    }};

    @RName("fsess")
    public static class $_ implements Factory {
        public Widget create(Widget parent, Object[] args) {
            int nact = (Integer) args[0];
            return (new Fightsess(nact, parent.getparent(GameUI.class).fv));
        }
    }

    @SuppressWarnings("unchecked")
    public Fightsess(int nact, Fightview fv) {
        this.fv = fv;
        pho = -40;
        this.actions = (Indir<Resource>[]) new Indir[nact];
        this.dyn = new boolean[nact];
    }

    public void presize() {
        resize(parent.sz);
        pcc = sz.div(2);
    }

    protected void added() {
        presize();
    }

    private void updatepos() {
        MapView map;
        Gob pl;
        if (((map = getparent(GameUI.class).map) == null) || ((pl = map.player()) == null) || (pl.sc == null))
            return;
        pcc = pl.sc;
        pho = (int) (pl.sczu.mul(20f).y) - 20;
    }

    private static final Resource tgtfx = Resource.local().loadwait("gfx/hud/combat/trgtarw");
    private final Map<Pair<Long, Resource>, Sprite> cfx = new CacheMap<Pair<Long, Resource>, Sprite>();
    private final Collection<Sprite> curfx = new ArrayList<Sprite>();

    private void fxon(long gobid, Resource fx) {
	MapView map = getparent(GameUI.class).map;
	Gob gob = ui.sess.glob.oc.getgob(gobid);
	if((map == null) || (gob == null))
	    return;
	Pair<Long, Resource> id = new Pair<Long, Resource>(gobid, fx);
	Sprite spr = cfx.get(id);
	if(spr == null)
	    cfx.put(id, spr = Sprite.create(null, fx, Message.nil));
	map.drawadd(gob.loc.apply(spr));
	curfx.add(spr);
    }

    public void tick(double dt) {
	for(Sprite spr : curfx)
	    spr.tick((int)(dt * 1000));
	curfx.clear();
    }

    private static final Text.Furnace ipf = new PUtils.BlurFurn(new Text.Foundry(Text.serif, 18, new Color(128, 128, 255)).aa(true), 1, 1, new Color(48, 48, 96));
    private final Text.UText<?> ip = new Text.UText<Integer>(ipf) {
        public String text(Integer v) {
            return ("IP: " + v);
        }

        public Integer value() {
            return (fv.current.ip);
        }
    };
    private final Text.UText<?> oip = new Text.UText<Integer>(ipf) {
        public String text(Integer v) {
            return ("IP: " + v);
        }

        public Integer value() {
            return (fv.current.oip);
        }
    };

    private static final Coord cmc = new Coord(150, 27);
    private static final Coord msz = new Coord(92, 12);
    private static final Coord o1c = new Coord(119, 9);
    private static final Coord d1c = new Coord(119, 33);
    private static final Coord o2c = new Coord(181, 9);
    private static final Coord d2c = new Coord(181, 33);

    public void draw(GOut g) {
        updatepos();
        double now = System.currentTimeMillis() / 1000.0;

        for (Buff buff : fv.buffs.children(Buff.class))
            buff.draw(g.reclip(pcc.add(-buff.c.x - Buff.cframe.sz().x - 20, buff.c.y + pho - Buff.cframe.sz().y), buff.sz));
        if (fv.current != null) {
            for (Buff buff : fv.current.buffs.children(Buff.class))
                buff.draw(g.reclip(pcc.add(buff.c.x + 20, buff.c.y + pho - Buff.cframe.sz().y), buff.sz));

            g.aimage(ip.get().tex(), pcc.add(-75, 0), 1, 0.5);
            g.aimage(oip.get().tex(), pcc.add(75, 0), 0, 0.5);

	        if(fv.lsrel.size() > 1)
		        fxon(fv.current.gobid, tgtfx);

            if (Config.showcddelta && fv.current != null) {
                Tex cdtex = cdvalues.get(fv.current.gobid);
                if (cdtex != null)
                    g.aimage(cdtex, pcc.add(0, 175), 0.5, 1);
            }
        }

        Coord mul = pcc.add(-meters.sz().x / 2, 40);
        if (now < fv.atkct) {
            double cd = fv.atkct - now;
            double a = (now - fv.atkcs) / (fv.atkct - fv.atkcs);
            g.chcolor(255, 0, 128, 224);
            g.fellipse(mul.add(cmc), new Coord(24, 24), Math.PI / 2 - (Math.PI * 2 * Math.min(1.0 - a, 1.0)), Math.PI / 2);
            g.chcolor();
            if (Config.showcooldown)
                g.atextstroked(cdfmt.format(cd), pcc.add(0, 27), 0.5, 0.5, Color.WHITE, Color.BLACK);
        }
        g.chcolor(255, 0, 0, 224);
        g.frect(mul.add(o1c).sub((int) Math.round(msz.x * fv.off), 0), new Coord((int) Math.round(msz.x * fv.off), msz.y));
        g.chcolor(0, 0, 255, 224);
        g.frect(mul.add(d1c).sub((int) Math.round(msz.x * fv.def), 0), new Coord((int) Math.round(msz.x * fv.def), msz.y));
        if (fv.current != null) {
            g.chcolor(255, 0, 0, 224);
            g.frect(mul.add(o2c), new Coord((int) Math.round(msz.x * fv.current.off), msz.y));
            g.chcolor(0, 0, 255, 224);
            g.frect(mul.add(d2c), new Coord((int) Math.round(msz.x * fv.current.def), msz.y));
        }
        g.chcolor();
        g.image(meters, mul);
        final int rl = 5;

        for (int i = 0; i < actions.length; i++) {
            Coord ca = pcc.add((actpitch * (i % rl)) - ((rl * actpitch) / 2), 110 + ((i / rl) * actpitch));
            Indir<Resource> act = actions[i];
            try {
                if (act != null) {
                    Tex img = act.get().layer(Resource.imgc).tex();
                    g.image(img, ca);
                    g.image(dyn[i] ? lframe : Buff.frame, ca.sub(Buff.imgoff));
                    if (i == use) {
                        g.chcolor(255, 0, 128, 255);
                        g.frect(ca.add(0, img.sz().y + 3), new Coord(img.sz().x, 5));
                        g.chcolor();
                    } else if(i == useb) {
                        g.chcolor(128, 0, 255, 255);
                        Coord cc = ca.add(img.sz().x / 2, img.sz().y + 5);
                        g.frect(cc.sub(2, 2), new Coord(5, 5));
                        g.chcolor();
                    }
                }
            } catch (Loading l) {
            }
            ca.x += actpitch;
        }
    }

    private Widget prevtt = null;

    public Object tooltip(Coord c, Widget prev) {
        for (Buff buff : fv.buffs.children(Buff.class)) {
            Coord dc = pcc.add(-buff.c.x - Buff.cframe.sz().x - 20, buff.c.y + pho - Buff.cframe.sz().y);
            if (c.isect(dc, buff.sz)) {
                Object ret = buff.tooltip(c.sub(dc), prevtt);
                if (ret != null) {
                    prevtt = buff;
                    return (ret);
                }
            }
        }
        if (fv.current != null) {
            for (Buff buff : fv.current.buffs.children(Buff.class)) {
                Coord dc = pcc.add(buff.c.x + 20, buff.c.y + pho - Buff.cframe.sz().y);
                if (c.isect(dc, buff.sz)) {
                    Object ret = buff.tooltip(c.sub(dc), prevtt);
                    if (ret != null) {
                        prevtt = buff;
                        return (ret);
                    }
                }
            }
        }
        final int rl = 5;
        for (int i = 0; i < actions.length; i++) {
            Coord ca = pcc.add((actpitch * (i % rl)) - ((rl * actpitch) / 2), 110 + ((i / rl) * actpitch));
            Indir<Resource> act = actions[i];
            try {
                if (act != null) {
                    Tex img = act.get().layer(Resource.imgc).tex();
                    if (c.isect(ca, img.sz())) {
                        if (dyn[i])
                            return ("Combat discovery");
                        return (act.get().layer(Resource.tooltip).t);
                    }
                }
            } catch (Loading l) {
            }
            ca.x += actpitch;
        }
        return (null);
    }

    public void uimsg(String msg, Object... args) {
        if (msg == "act") {
            int n = (Integer) args[0];
            if (args.length > 1) {
                Indir<Resource> res = ui.sess.getres((Integer) args[1]);
                actions[n] = res;
                dyn[n] = ((Integer) args[2]) != 0;
            } else {
                actions[n] = null;
            }
        } else if (msg == "use") {
            this.use = (Integer) args[0];
            this.useb = (Integer)args[1];
        } else if (msg == "used") {
            Indir<Resource> act = actions[(Integer) args[0]];
            try {
                if (act != null) {
                    Resource.Tooltip tt = act.get().layer(Resource.Tooltip.class);
                    if (tt != null) {
                        Integer cd = atkcds.get(tt.t);
                        if (cd != null && fv.current != null) {
                            double inc = fv.atkcd - cd;
                            int cddelta = -(int) (inc / (double) cd * 100);
                            cdvalues.put(fv.current.gobid, Text.renderstroked(cddelta + " %", cddelta < 0 ? cdclrneg : cdclrpos, Color.BLACK, cdfndr).tex());
                        }
                    }
                }
            } catch (Loading l) {
            }
        } else {
            super.uimsg(msg, args);
        }
    }

    public boolean globtype(char key, KeyEvent ev) {
        if (Config.userazerty)
            key = Utils.azerty2qwerty(ev.getKeyChar());

        if((ev.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK | KeyEvent.META_DOWN_MASK | KeyEvent.ALT_DOWN_MASK)) == 0) {
            int n = -1;
            switch(key) {
                case '1': n = 0; break;
                case '2': n = 1; break;
                case '3': n = 2; break;
                case '4': n = 3; break;
                case '5': n = 4; break;
                case 'q': n = 5; break;
                case 'w': n = 6; break;
                case 'e': n = 7; break;
                case 'r': n = 8; break;
                case 't': n = 9; break;
            }
            if((n >= 0) && (n < actions.length)) {
                wdgmsg("use", n);
                return(true);
            }
        } else if((key == 9) && ((ev.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0)) {
            Fightview.Relation cur = fv.current;
            if(cur != null) {
                fv.lsrel.remove(cur);
                fv.lsrel.addLast(cur);
            }
            fv.wdgmsg("bump", (int)fv.lsrel.get(0).gobid);
            return(true);
        }
        return(super.globtype(key, ev));
    }
}
