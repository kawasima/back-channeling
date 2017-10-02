(function() {
    var availableCharacters = 'abcdefghijklmnopqrstuvwxyz0123456789';
    var password = document.getElementById("password-field");
    var token    = document.getElementById("token-field");
    var humanSvg = document.getElementById("human-svg");
    var robotSvg = document.getElementById("robot-svg");

    function randomString(n) {
        var s = '';
        for (var i=0; i < n; i++) {
            s += availableCharacters.charAt(Math.random() * availableCharacters.length);
        }
        return s;
    }

    function switchToBot(e) {
        robotSvg.setAttribute("class", "account-type on");
        humanSvg.setAttribute("class", "account-type off");
        token.querySelector("input[name='token-credential/token']").value = randomString(16);
        password.querySelector("input[name='password-credential/password']").value = '';
        password.style['display'] = 'none';
        token.style['display'] = 'block';
    }

    function switchToHuman(e) {
        humanSvg.setAttribute("class", "account-type on");
        robotSvg.setAttribute("class", "account-type off");
        token.querySelector("input[name='token-credential/token']").value = '';
        password.style['display'] = 'block';
        token.style['display'] = 'none';
    }

    humanSvg.addEventListener("click", switchToHuman);
    robotSvg.addEventListener("click", switchToBot);
    switchToHuman();
})();
