import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.*;
import javax.imageio.ImageIO;

/** Вбудовані 16x16 текстури блоків для іконок збірок. */
final class BlockTex {
    private BlockTex() { }

    static final String[] NAMES = {
            "chiseled_bookshelf_occupied",
            "copper_block",
            "copper_bulb",
            "copper_bulb_lit",
            "diamond_block",
            "emerald_block",
            "lime_concrete_powder",
            "oxidized_copper_bulb",
            "oxidized_copper_bulb_lit",
            "warped_nylium",
            "warped_nylium_side",
            "grass_block_side"
    };

    private static final Map<String, String> DATA = new HashMap<>();
    private static final Map<String, BufferedImage> CACHE = new HashMap<>();

    static {
        DATA.put("chiseled_bookshelf_occupied", "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAMAAAAoLQ9TAAAAeFBMVEXyyVjMpmy8mGKzjFHDjwCfhE1rnjq7b1Sbd0LUSm6tYUanQ+UilJRXhSp8Yj62PFwWhYWOO8KFYgB+RjN6MacnWaVAYR+RL0mRMxQQYWFnK45rPCsgTZGAKkEyTxcPUlJOOh5qJQ9JJl8XOWpLH2cPLl01HEYZEwrUzIfpAAAAq0lEQVR42kWO0RaCIBBEF0aKLLW0MEwQQ+X//zAwrfuyZ+fszgwxxgCWEEIAgkRIKGVtmKZlKYkPVz0EVSgbbufuXRL0RQ+uOCjruvu0SMKTstFllNW7MGb1GIrD9iKp1D+PeJFMAc5jKOcciMkESBnnusgISdMaE1a8n+eSmKlaE/Jj8wr9w8+S2JqYBJcKgFCdKvPtEQvswv8FJNrk0eRR8H3y2FoAPCKBD8RcEcc5N82wAAAAAElFTkSuQmCC");
        DATA.put("copper_block", "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQBAMAAADt3eJSAAAAGFBMVEXjgmzWe1vIdFbCa0yyYkenWkCaUDiQSTE/4tshAAAAeUlEQVR42hXMwQ3CMBAEwN0tILlLAYgg8QcBDVjQP/xBchrANuLN4XznMQJpwHSRdkenzy4stZgeEFM02yYKz+rSUBUBszlMKKTGBYrW4Y2scE5jHiCao0MRaWOOqIKjcD15tzrE18VUPNfUZdN+gVfIP6f9+XY9/AEjBS3TYVj+zAAAAABJRU5ErkJggg==");
        DATA.put("copper_bulb", "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQBAMAAADt3eJSAAAAIVBMVEXjgmzWe1vIdFbCa0yyYkenWkCaUDiQSTGGTilzQR9fMxUkk54eAAAAjElEQVR42hXMsQnCQBiG4ff7DQiKeJfCwkIS7cXGXjJLukzgFmYDWx3DDRxAggscFxFEiEQtn+YRYhpJV2bLUz065964h8emuWIqCJFCybAMgvJjPzvXNyTId5kHDdbtdn7haUiAQ6Mjr/2BLiHEcRbaiSEgOuxdOZyq+j8TKZxpkU5jfus123mCfP8FWpwn5UzbAQsAAAAASUVORK5CYII=");
        DATA.put("copper_bulb_lit", "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQBAMAAADt3eJSAAAAJFBMVEX/99L/3Zn2yG3/qFHjgmzWe1vIdFbCa0yyYkenWkCaUDiQSTGvwSNvAAAAiklEQVR42g3MMQrCQBAF0P+H9JldsdBCWYWUioWNlUTB2taT5TzewANIyAU2myDaCJNtXvl4JcoEvxXZxM764ARdHPftC8LaYp9qFv8mfc2ahSAmqLUQOMxChrfdcFw+8RGQABS4BMzv8CdBj3GdKeDxAwOkqFQdq0OeqYPWKlz5cghv4+PsEOlsAnprKch0ZNzmAAAAAElFTkSuQmCC");
        DATA.put("diamond_block", "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQBAMAAADt3eJSAAAAG1BMVEX////V//ae/utw+/Bl9eNL7eY94OUVwsYOur1j/GYQAAAAeklEQVR42hXLsRHCMBAEwPsf5dw5JxB0QAfMQAnUSStEhIALsF8iBj3D5msXKIz5Lp/rLmza3kueFuzrAkfOhnjRp6C4GhzNlE8dS4DxdcFhYK9oLloaMhxgYw642+jATcX42G/Gvw+X5hpuvTLE5nlQNq29TOycFOcf/6MxqUgMRiYAAAAASUVORK5CYII=");
        DATA.put("emerald_block", "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQBAMAAADt3eJSAAAAElBMVEWC9q1B84QX3WIXxUQarjUcmCkR/ex7AAAAaklEQVR42iXJyxXCMAxE0SGH7G2OXUCEstcvDQTTAfTfCop5q6sR/F9AgFKWrUPyfHl0qOJG1HK5pmhrQlDvvENFKjU+oCplQlQpIvKVGHzhlIdxO2DnyBIqi3vlHTZmHWZPI99WvL+zzw+IFhXpkE2SQAAAAABJRU5ErkJggg==");
        DATA.put("lime_concrete_powder", "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAACQElEQVQoUwXBXY4cNRAA4CqX23b/zfTsZEM2ES9BAgkkJK7BQ66SmyUnQVyABwQrtEkmm+npP7ftquL78P2HH0NHbW3SzlnxUMOy6rZD3+Ge1FlQRQANfZWnDIS2Pxouag0kS7TkuRg08GIgsuidgjehwJbLcs1DR9coJjF2vYUK14ltMAIgANvOKbMIbLcioPOqyvD0ld8+NCZHfX4uaWFbQett5bB1WBjSja8j//L6t2ljFyj0ZCw8Pk7m1x/e3B1wK6YLODO8GKwLxla0iMkJ/nr6U9Gcg6edfUNN7emn3/2aQVnvXlXjpeyiImoNegeus+sqy8iV1z0rgs5TsTlxfbDbqv895rzKMFTTwsKsAq4qXW+bATtLGSFG7o5k655ANTQIosN3pAyAeDiRV/z8LctaVOFRS+2gbu2ysKkcBU9kjQ+UGZilbcgp7LkMLyve5f7kW6fBgeyFixpesoikJJw5Rk0M61x+fvVu3nS7ZuPNl6nkArcF5hWazhrwxAI8piTYeeh7cg7/+Oejby0FK4gNSSpwPNvgwAibuCkinB7COmlkGMdSVxCjcipGmFTZ2fuDnS65rvDLUzGHwcao88TD2caMe4Ixwr4J71KK7hlky5XHuxOpanckM954OBIopGvyRl0FkvXhe5eQ0Nu2o1jwcivLKpti8GC9h3XllycvCpc5dQGWqLeRO8vzDVrkxpMa8+2ZAaE5k4mrGsJP1/3vf7fzvV2TNhaampKaQ0+7GlbNSd6+aaoKLpf8Px7BdxfUOL/tAAAAAElFTkSuQmCC");
        DATA.put("oxidized_copper_bulb", "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQBAMAAADt3eJSAAAAIVBMVEVuxZ9owJxZspJPq5BToXhMlIQ+gWs9fGY5blk7ZlUxVEYR3jc2AAAAiElEQVR42iXLMQrCQBBG4Z+VbXIEK9EIsmVCwGwtQjzAKrmFV0g1064IM6WIYOaUrqR7xfcAhw2wO8Nt7RPsdIPz8gjsSySZAqcBlckU2WZUQjkQl1B9jqozXNNe7203lL1EU/+xvdZm3wULS8GUI6mg9pIj+x77JPnCqceqs/do7REHYlZh+gFA0i9UNGpN4wAAAABJRU5ErkJggg==");
        DATA.put("oxidized_copper_bulb_lit", "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQBAMAAADt3eJSAAAAMFBMVEXmtFBuxaBowJzUmUNZspLEiThPq5BPq49SoXq/cUFMlIRKlYI7gms9fGY1cFk1aVVIPb7bAAAAiElEQVR42mMQFBF0FBR072EQcZk523Nmxz4GEengcFdTaSBjT7CJs+mefgbJUAOWAubQ+QySxgwsDgzGQEYwg9ViBtP5DCIlZWsvpVX3A7UDGSke/SApI2UG03kwxXEw7XEMHtLBJsWm0vUM3nuCQ1pN99QziFfPnL5zZnkdg9+79+///3v/DgDinSytBtrOEgAAAABJRU5ErkJggg==");
        DATA.put("warped_nylium", "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQBAMAAADt3eJSAAAAFVBMVEUUtIVWg1MRm4UWfoZFa1I+T1gWYVsza5xBAAAAeklEQVR42gVAwQ3CMAw8EAtEeIIW9d8k9btWEw9QSv5gJ/uPgBB6ie1cMthDsiNNuL4PjRoZtenIxAnU5yY33aF28WkUYL7+ZvEGLc/5vlfDqsxLoYZhLpvbC7wT9Z4H6lrG25YDNiUTjRWDJQdKDP9kux3eIFqHxM3+4fwgUctjMDAAAAAASUVORK5CYII=");
        DATA.put("warped_nylium_side", "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQBAMAAADt3eJSAAAAJ1BMVEUUtIURm4UWfoaFQkI+T1gWYVtyMjJlKCgwPDVXISFQGxtRFRVBFhb3QIPYAAAAmElEQVR42g3JQQqCQBgG0M8beIQm7AxtY0jwAAqzH5qh3NYvjNu0AfeJ1bYkcV2KrUM8Vb3tA2OeO2fMAXe568ycBULOvWi1Fgh8f515mUUnrChCO2Doenu1RYV+/y2roiJsRa0ifdQwZO6BOhBOUo9EscCZ9ChVO8Hk5vNIpwvemzxtXpT+S8ZNKWPUavlsiRI0tKNbrZIf63I5FwQW/IUAAAAASUVORK5CYII=");
        DATA.put("grass_block_side", "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAMAAAAoLQ9TAAAAeFBMVEWcy2yXxmeTwmOSwWKQv2CNvF2KuVp/v1V+vlSDslOBsFF2tkx1tUt0tEpzs0lxsUdwsEa5hVxvr0VtrUNsrEJrq0FqqkBpqT9oqD5npz2Hh4dmpjxkpDpiojhhoTdgoDZfnzVXly2WbEpQkCZsbGx0WER5VTpZPSnN78OwAAAAnElEQVR42jWNCw7CMAxDw/8/CBuMbTBGGPb9b4hbQRtZT3Gfaodd0XXnoVrvrk3dv0vbV8vthrfHMJ0XfdVcbEG71zzxsJpN+HrSOJJHkgNLgoQFwhkQc0QQBkYISEADN1e2Ak+jyhKHlGDOsJS6FCNZhkg2oZMlLbIfdJfUwrKoZpRCjSnzBj8pKfgg1U6ZYYlAheP/ratuocjvvsNMH5BFYTKgAAAAAElFTkSuQmCC");
    }

    static boolean has(String id) { return DATA.containsKey(id); }

    static synchronized BufferedImage get(String id) {
        BufferedImage im = CACHE.get(id);
        if (im != null) return im;
        try {
            im = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(DATA.get(id))));
        } catch (Exception e) {
            im = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        }
        BufferedImage argb = new BufferedImage(im.getWidth(), im.getHeight(), BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = argb.createGraphics();
        g.drawImage(im, 0, 0, null);
        g.dispose();
        CACHE.put(id, argb);
        return argb;
    }
}
