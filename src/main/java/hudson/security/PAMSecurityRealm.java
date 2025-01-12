/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.security;

import hudson.Extension;
import hudson.Functions;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.os.PosixAPI;
import hudson.security.pam.Messages;
import hudson.util.FormValidation;
import jenkins.model.IdStrategy;
import jenkins.model.Jenkins;
import org.acegisecurity.AuthenticationException;
import org.acegisecurity.BadCredentialsException;
import org.acegisecurity.GrantedAuthority;
import org.acegisecurity.GrantedAuthorityImpl;
import org.acegisecurity.userdetails.User;
import org.acegisecurity.userdetails.UserDetails;
import org.acegisecurity.userdetails.UsernameNotFoundException;
import org.jruby.ext.posix.FileStat;
import org.jruby.ext.posix.Group;
import org.jruby.ext.posix.POSIX;
import org.jruby.ext.posix.Passwd;
import org.jvnet.libpam.PAM;
import org.jvnet.libpam.PAMException;
import org.jvnet.libpam.UnixUser;
import org.jvnet.libpam.impl.CLibrary;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.springframework.dao.DataAccessException;

import java.io.File;
import java.util.Set;
import java.util.logging.Logger;

/**
 * {@link SecurityRealm} that uses Unix PAM authentication.
 *
 * @author Kohsuke Kawaguchi
 * @since 1.282
 */
public class PAMSecurityRealm extends AbstractPasswordBasedSecurityRealm {
    public final String serviceName;

    @DataBoundConstructor
    public PAMSecurityRealm(String serviceName) {
        serviceName = Util.fixEmptyAndTrim(serviceName);
        if(serviceName==null)   serviceName="sshd"; // use sshd as the default
        this.serviceName = serviceName;
    }

    @Override
    protected synchronized UserDetails authenticate(String username, String password) throws AuthenticationException {
        try {
            UnixUser uu = new PAM(serviceName).authenticate(username, password);

            // I never understood why Acegi insists on keeping the password...
            return new User(username,"",true,true,true,true, toAuthorities(uu));
        } catch (PAMException e) {
            throw new BadCredentialsException(e.getMessage(),e);
        }
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException, DataAccessException {
        if(!UnixUser.exists(username))
            throw new UsernameNotFoundException("No such Unix user: "+username);
        try {
            UnixUser uu = new UnixUser(username);
            // return some dummy instance
            return new User(username,"",true,true,true,true, toAuthorities(uu));
        } catch (PAMException e) {
            throw new UsernameNotFoundException("Failed to load information about Unix user "+username,e);
        }
    }

    private static GrantedAuthority[] toAuthorities(UnixUser u) {
        Set<String> grps = u.getGroups();
        GrantedAuthority[] groups = new GrantedAuthority[grps.size()+1];
        int i=0;
        for (String g : grps)
            groups[i++] = new GrantedAuthorityImpl(g);
        groups[i++] = AUTHENTICATED_AUTHORITY;
        return groups;
    }

    @Override
    public GroupDetails loadGroupByGroupname(final String groupname) throws UsernameNotFoundException, DataAccessException {
        final String group;
        if(groupname.startsWith("@")) {
            group = groupname.substring(1);
        } else {
            group = groupname;
        }
        if(CLibrary.libc.getgrnam(group)==null)
            throw new UsernameNotFoundException(group);
        return new GroupDetails() {
            @Override
            public String getName() {
                return group;
            }
        };
    }

    /**
     * {@inheritDoc}
     * @since 1.2
     */
    @Override
    public IdStrategy getUserIdStrategy() {
        return DescriptorImpl.STRATEGY;
    }

    /**
     * {@inheritDoc}
     * @since 1.2
     */
    @Override
    public IdStrategy getGroupIdStrategy() {
        return DescriptorImpl.STRATEGY;
    }

    public static final class DescriptorImpl extends Descriptor<SecurityRealm> {
        public String getDisplayName() {
            return Messages.PAMSecurityRealm_DisplayName();
        }

        /**
         * NSS/PAM databases are case sensitive... unless running OS-X (think different™)
         * @since 1.2
         */
        private static final IdStrategy STRATEGY =
                Util.fixNull(System.getProperty("os.name")).contains("OS X")
                        ? IdStrategy.CASE_INSENSITIVE
                        : new IdStrategy.CaseSensitive();

        @RequirePOST
        public FormValidation doTest() {
            Jenkins jenkins = Jenkins.getInstance();
            if (jenkins == null || !jenkins.hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            File s = new File("/etc/shadow");
            if(s.exists() && !s.canRead()) {
                // it looks like shadow password is in use, but we don't have read access
                LOGGER.fine("/etc/shadow exists but not readable");
                POSIX api = PosixAPI.get();
                FileStat st = api.stat("/etc/shadow");
                if(st==null)
                    return FormValidation.error(Messages.PAMSecurityRealm_ReadPermission());

                Passwd pwd = api.getpwuid(api.geteuid());
                String user;
                if(pwd!=null)   user=Messages.PAMSecurityRealm_User(pwd.getLoginName());
                else            user=Messages.PAMSecurityRealm_CurrentUser();

                String group;
                Group g = api.getgrgid(st.gid());
                if(g!=null)     group=g.getName();
                else            group=String.valueOf(st.gid());

                if ((st.mode()&FileStat.S_IRGRP)!=0) {
                    // the file is readable to group. Jenkins should be in the right group, then
                    return FormValidation.error(Messages.PAMSecurityRealm_BelongToGroup(user, group));
                } else {
                    Passwd opwd = api.getpwuid(st.uid());
                    String owner;
                    if(opwd!=null)  owner=opwd.getLoginName();
                    else            owner=Messages.PAMSecurityRealm_Uid(st.uid());

                    return FormValidation.error(Messages.PAMSecurityRealm_RunAsUserOrBelongToGroupAndChmod(owner, user, group));
                }
            }
            return FormValidation.ok(Messages.PAMSecurityRealm_Success());
        }
    }

    @Extension
    public static DescriptorImpl install() {
        if(!Functions.isWindows()) return new DescriptorImpl();
        return null;
    }

    private static final Logger LOGGER = Logger.getLogger(PAMSecurityRealm.class.getName());
}
