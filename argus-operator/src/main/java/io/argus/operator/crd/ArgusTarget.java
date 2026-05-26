package io.argus.operator.crd;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("argus.io")
@Version("v1alpha1")
@Kind("ArgusTarget")
@Plural("argustargets")
@Singular("argustarget")
@ShortNames("at")
public class ArgusTarget extends CustomResource<ArgusTargetSpec, ArgusTargetStatus> implements Namespaced {
}
