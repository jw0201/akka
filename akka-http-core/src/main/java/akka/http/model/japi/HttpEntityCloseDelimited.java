/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import akka.stream.scaladsl2.FlowWithSource;
import akka.util.ByteString;

/**
 * Represents an entity without a predetermined content-length. Its length is implicitly
 * determined by closing the underlying connection. Therefore, this entity type is only
 * available for Http responses.
 */
public abstract class HttpEntityCloseDelimited implements ResponseEntity {
    public abstract FlowWithSource<?, ByteString> data();
}
