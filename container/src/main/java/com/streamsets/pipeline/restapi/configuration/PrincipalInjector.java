/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.restapi.configuration;

import org.glassfish.hk2.api.Factory;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import java.security.Principal;

public class PrincipalInjector implements Factory<Principal> {
  private Principal principal;

  @Inject
  public PrincipalInjector(HttpServletRequest request) {
    principal = new Principal() {
      @Override
      public String getName() {
        return "nobody";
      }
    };
  }

  @Override
  public Principal provide() {
    return principal;
  }

  @Override
  public void dispose(Principal principal) {
  }

}
