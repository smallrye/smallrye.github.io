package emitter;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

// tag::intro[]
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

@ApplicationScoped
public class MyImperativeBean {

    @Inject
    @Channel("prices")
    Emitter<Double> emitter;

    // ...

    public void send(double d) {
        emitter.send(d);
    }
}

// end::intro[]
