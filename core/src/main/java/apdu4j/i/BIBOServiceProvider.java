package apdu4j.i;

import apdu4j.BIBO;

import java.util.function.Function;
import java.util.function.Supplier;

public interface BIBOServiceProvider extends Supplier<Function<BIBO, Boolean>> {
}
