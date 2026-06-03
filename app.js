(function () {
  var DATA_URL = "./data/skills.json";
  var list = document.getElementById("skills-list");
  var status = document.getElementById("status");
  var itemCount = document.getElementById("item-count");
  var template = document.getElementById("skill-card-template");

  function formatStars(value) {
    var number = Number(value) || 0;
    return number.toLocaleString("en-US");
  }

  function setStatus(message, isError) {
    status.textContent = message;
    status.hidden = !message;
    status.style.borderLeftColor = isError ? "#b34028" : "#a9771e";
  }

  function appendItems(container, items) {
    items.forEach(function (item) {
      var fragment = template.content.cloneNode(true);
      var card = fragment.querySelector(".skill-card");
      var rank = fragment.querySelector(".rank-badge");
      var category = fragment.querySelector(".category");
      var link = fragment.querySelector(".repo-link");
      var stars = fragment.querySelector(".stars");
      var starsValue = fragment.querySelector(".stars strong");
      var description = fragment.querySelector(".description");
      var pros = fragment.querySelector(".pros");
      var cons = fragment.querySelector(".cons");

      card.setAttribute("data-rank", item.rank);
      rank.textContent = "#" + item.rank;
      rank.setAttribute("aria-label", "排名第 " + item.rank);
      category.textContent = item.category || "Uncategorized";
      link.textContent = item.name;
      link.href = item.repo_url;
      description.textContent = item.description || "";
      stars.setAttribute("aria-label", formatStars(item.stars) + " stars");
      starsValue.textContent = formatStars(item.stars);

      (item.pros || []).forEach(function (text) {
        var li = document.createElement("li");
        li.textContent = text;
        pros.appendChild(li);
      });

      (item.cons || []).forEach(function (text) {
        var li = document.createElement("li");
        li.textContent = text;
        cons.appendChild(li);
      });

      container.appendChild(fragment);
    });
  }

  function normalizeSkills(data) {
    if (!Array.isArray(data)) {
      throw new Error("skills.json must be an array");
    }

    return data
      .slice()
      .sort(function (a, b) {
        return Number(a.rank) - Number(b.rank);
      })
      .slice(0, 10);
  }

  fetch(DATA_URL)
    .then(function (response) {
      if (!response.ok) {
        throw new Error("Failed to load " + DATA_URL + ": " + response.status);
      }
      return response.json();
    })
    .then(function (data) {
      var skills = normalizeSkills(data);
      list.innerHTML = "";
      appendItems(list, skills);
      itemCount.textContent = skills.length + " 条";
      setStatus("", false);
    })
    .catch(function (error) {
      itemCount.textContent = "0 条";
      setStatus("数据加载失败：" + error.message, true);
      console.error(error);
    });
})();
