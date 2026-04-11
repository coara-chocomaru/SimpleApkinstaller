package com.coara.simpleapkinstaller.model;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

public class SignerMaterials {
    public final PrivateKey privateKey;
    public final List<X509Certificate> certificates;

    public SignerMaterials(PrivateKey privateKey, List<X509Certificate> certificates) {
        this.privateKey = privateKey;
        this.certificates = certificates;
    }
}
