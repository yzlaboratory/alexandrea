package dev.yzlaboratory.alexandrea.auth;

public class SingleUseLinkRejectedException extends RuntimeException {

    private final SingleUseLinkKind kind;

    public SingleUseLinkRejectedException(SingleUseLinkKind kind) {
        super(kind.rejectionMessage());
        this.kind = kind;
    }

    public SingleUseLinkKind kind() {
        return kind;
    }
}
