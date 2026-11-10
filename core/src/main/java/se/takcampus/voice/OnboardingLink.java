package se.takcampus.voice;

import java.net.URI;
import java.util.regex.Pattern;

/** A fragment bearer ticket is never placed in a request URL, log or redirect. */
public final class OnboardingLink {
    public final URI origin;
    public final String ticket;
    private OnboardingLink(URI origin, String ticket) { this.origin = origin; this.ticket = ticket; }
    public static OnboardingLink parse(String value) {
        if (value.length() > AudioLinkCodec.MAX_LINK) throw new IllegalArgumentException("Link too long");
        URI u = URI.create(value);
        String ticket = u.getRawFragment();
        if (!"https".equals(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null || u.getRawQuery() != null
                || (u.getPort() != -1 && u.getPort() != 443) || !"/api/onboarding/cards/watch-voice".equals(u.getRawPath())
                || ticket == null || !Pattern.matches("[A-Za-z0-9_-]{43}", ticket)) throw new IllegalArgumentException("Invalid setup link");
        return new OnboardingLink(URI.create("https://" + u.getRawAuthority()), ticket);
    }
    public URI redeemUrl() { return origin.resolve("/api/onboarding/cards/watch-voice/redeem"); }
}
